package de.schaefer.eosgps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

private const val TAG = "GpsService"
private const val CHANNEL_ID = "eos-gps-tracking"
private const val NOTIF_ID = 42
private const val MIN_SEND_INTERVAL_MS = 10_000L
private const val RSSI_POLL_INTERVAL_MS = 5_000L
// Camera advertises ~3s asleep, ~200ms awake. 8s of silence while we're not
// connected is a confident signal the camera is gone (battery out, out of
// range, powered off via the switch beyond BLE-standby).
private const val AD_STALENESS_THRESHOLD_MS = 8_000L
private const val STALENESS_CHECK_INTERVAL_MS = 2_000L
// When a GATT session drops (camera went to standby, briefly out of range,
// etc.) we keep the foreground service alive for this window and run the
// internal LOW_LATENCY scan, so a quick reconnect is instantaneous. After
// the window we hand off to the OS-offloaded scan (CanonScanRegistrar) and
// let the service exit, so the notification disappears when the camera is
// genuinely gone.
private const val RECONNECT_GRACE_MS = 30_000L
// Hard cap on the foreground LOW_LATENCY scan. Android silently downgrades
// any direct-ScanCallback scan to SCAN_MODE_OPPORTUNISTIC after ~30 min,
// which would make a camera wake-up go unnoticed. We bail out much earlier
// than that and either hand off to the OS scan or exit the service, so the
// user never ends up in a half-dead "scan running, nothing happens" state.
private const val INTERNAL_SCAN_MAX_MS = 120_000L

// Canon advertisement decoding lives in CanonAd.kt — shared between the
// OS-offloaded scan (CanonScanRegistrar) and the in-service scan.

class GpsTrackingService : Service() {

    private var gatt: CanonGattClient? = null
    private var bondedDevice: BluetoothDevice? = null
    private var scanner: BluetoothLeScanner? = null
    @Volatile private var scanning = false
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastSentAt = 0L
    // UI time = last advertisement seen; watchdog time = last camera contact of
    // any kind (advertisement OR active GATT session). Split so the UI doesn't
    // falsely claim "adv 0s ago" right after a disconnect, while the watchdog
    // still doesn't flip UNSEEN during transitions.
    @Volatile private var watchdogAnchorMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val rssiPollRunnable = object : Runnable {
        override fun run() {
            gatt?.readRssi()
            mainHandler.postDelayed(this, RSSI_POLL_INTERVAL_MS)
        }
    }

    // Flip cameraPower to UNSEEN after too long without any contact. Covers
    // battery-pull / out-of-range / physical-off where no disconnect fires.
    private val staleWatchdog = object : Runnable {
        override fun run() {
            val cur = TrackingState.cameraPower.value
            if (gatt == null && watchdogAnchorMs > 0 && cur != CameraPowerState.UNSEEN) {
                val elapsed = SystemClock.elapsedRealtime() - watchdogAnchorMs
                if (elapsed > AD_STALENESS_THRESHOLD_MS) {
                    TrackingState.log("no contact for ${elapsed / 1000}s → unseen")
                    TrackingState.cameraPower.value = CameraPowerState.UNSEEN
                    updateNotification()
                }
            }
            mainHandler.postDelayed(this, STALENESS_CHECK_INTERVAL_MS)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            onLocation(loc)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleAdvertisement(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handleAdvertisement(r)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed: $errorCode")
            TrackingState.log("scan failed: $errorCode")
            scanning = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            else -> {
                val awakeHint = intent?.getBooleanExtra(EXTRA_AWAKE_AD_HINT, false) == true
                startTracking(awakeHint)
            }
        }
        // NOT_STICKY — the offloaded scan re-wakes us on the next advertisement,
        // so we don't want Android to auto-restart us after a crash or kill.
        // (An earlier START_STICKY caused a crash-loop when the Android 14
        // FGS type validation rejected location-type starts from the broadcast.)
        return START_NOT_STICKY
    }

    // --- Lifecycle ---

    @SuppressLint("MissingPermission")
    private fun startTracking(awakeHint: Boolean = false) {
        // Awake hint means the OS-offloaded scan already observed an
        // awake-shaped advertisement right before starting us; we can skip
        // the self-run scan and dive straight into GATT.
        if (TrackingState.serviceRunning.value) {
            // Service already running (e.g. during the reconnect grace
            // window). Cancel any pending giveup and keep going.
            mainHandler.removeCallbacks(reconnectGiveup)
            return
        }

        val notif = buildNotification("Scanning for camera…")
        // FGS type selection on Android 14+:
        //   - `connectedDevice` is always safe: we target a bonded BT device.
        //   - `location` additionally unlocks FusedLocationProvider while
        //     the app itself is in the background — but the platform only
        //     lets us declare `location` when we either have foreground UI
        //     (not the case when started from a scan broadcast) or we hold
        //     ACCESS_BACKGROUND_LOCATION as an exemption.
        // Without `location`, location updates only flow while MainActivity
        // is visible. So we add it whenever the user granted background
        // location — then GPS works even with the screen off.
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasBackgroundLocation(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, types)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        TrackingState.serviceRunning.value = true
        TrackingState.resetSession()
        watchdogAnchorMs = 0L

        val device = findBondedCanon(this)
        if (device == null) {
            TrackingState.log("No bonded EOS device found")
            stopSelf()
            return
        }
        bondedDevice = device

        startLocationUpdates()
        mainHandler.postDelayed(staleWatchdog, STALENESS_CHECK_INTERVAL_MS)

        if (awakeHint) {
            TrackingState.log("started by awake-ad → connecting directly")
            TrackingState.cameraPower.value = CameraPowerState.POWER_ON
            markAdvertisement()
            startGattSession()
        } else {
            startScan()
        }
    }

    private fun stopTracking() {
        Log.i(TAG, "stopTracking")
        mainHandler.removeCallbacks(rssiPollRunnable)
        mainHandler.removeCallbacks(staleWatchdog)
        mainHandler.removeCallbacks(reconnectGiveup)
        mainHandler.removeCallbacks(scanTimeout)
        fused.removeLocationUpdates(locationCallback)
        stopScan()
        gatt?.stopAndDisconnect()
        gatt = null
        bondedDevice = null
        TrackingState.serviceRunning.value = false
        TrackingState.connState.value = ConnState.IDLE
        TrackingState.gpsState.value = CanonGpsState.UNKNOWN
        TrackingState.cameraPower.value = CameraPowerState.UNSEEN
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(rssiPollRunnable)
        mainHandler.removeCallbacks(staleWatchdog)
        mainHandler.removeCallbacks(reconnectGiveup)
        mainHandler.removeCallbacks(scanTimeout)
        fused.removeLocationUpdates(locationCallback)
        stopScan()
        gatt?.stopAndDisconnect()
        gatt = null
        TrackingState.serviceRunning.value = false
        super.onDestroy()
    }

    // --- BLE scan ---

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        val device = bondedDevice ?: return
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val s = mgr.adapter?.bluetoothLeScanner
        if (s == null) { TrackingState.log("no BLE scanner available"); return }
        scanner = s
        // HW-filter: bonded Canon MAC + Canon company id. No byte-5
        // constraint — we want to see both power states so the UI +
        // staleness watchdog see awake ↔ asleep transitions live.
        val filter = ScanFilter.Builder()
            .setDeviceAddress(device.address)
            .setManufacturerData(CanonAd.COMPANY_ID, ByteArray(0), ByteArray(0))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            s.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            mainHandler.removeCallbacks(scanTimeout)
            mainHandler.postDelayed(scanTimeout, INTERNAL_SCAN_MAX_MS)
            TrackingState.log("→ scanning (HW-filtered to ${device.address} + Canon mfg)")
            Log.i(TAG, "startScan started for ${device.address}")
        } catch (e: SecurityException) {
            TrackingState.log("scan perm denied: ${e.message}")
            Log.e(TAG, "scan perm denied", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        mainHandler.removeCallbacks(scanTimeout)
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    /**
     * Hard-stop the internal scan before Android silently downgrades it to
     * SCAN_MODE_OPPORTUNISTIC at ~30 min. If auto-start is on, hand off
     * (re-arm for a fresh edge, then exit the service). Otherwise exit and
     * leave the user to start again — at least no silent decay.
     */
    private val scanTimeout = Runnable {
        if (!scanning || gatt != null) return@Runnable
        val ctx = applicationContext
        if (Prefs.autoStartEnabled(ctx)) {
            TrackingState.log("internal scan timed out (${INTERNAL_SCAN_MAX_MS / 60000}min) → hand off to OS scan")
            CanonScanRegistrar.rearm(ctx)
        } else {
            TrackingState.log("internal scan timed out — stopping to avoid silent throttling")
        }
        stopTracking()
    }

    private fun handleAdvertisement(result: ScanResult) {
        val mfg = result.scanRecord?.getManufacturerSpecificData(CanonAd.COMPANY_ID) ?: return
        if (mfg.size <= CanonAd.POWER_BYTE_INDEX) return
        markAdvertisement()
        val newState = CanonAd.powerStateFromByte(mfg[CanonAd.POWER_BYTE_INDEX]) ?: return
        val prev = TrackingState.cameraPower.value
        if (prev != newState) {
            TrackingState.cameraPower.value = newState
            TrackingState.log("adv: ${prev.label()} → ${newState.label()}")
        }
        if (newState == CameraPowerState.POWER_ON && gatt == null) {
            stopScan()
            startGattSession()
        }
    }

    // --- GATT session ---

    @SuppressLint("MissingPermission")
    private fun startGattSession() {
        val device = bondedDevice ?: return
        if (gatt != null) return
        TrackingState.log("camera awake → connecting GATT")
        val client = CanonGattClient(
            context = applicationContext,
            onLog = { TrackingState.log(it) },
            onStateChange = {
                TrackingState.connState.value = it
                updateNotification()
                if (it == ConnState.IDLE) onGattDisconnected()
            },
            onGpsState = {
                TrackingState.gpsState.value = it
                if (it == CanonGpsState.READY_TO_RECEIVE &&
                    TrackingState.sessionStartedAt.value == null) {
                    TrackingState.sessionStartedAt.value = SystemClock.elapsedRealtime()
                }
                updateNotification()
            },
            onRssi = { TrackingState.rssi.value = it },
            onWriteResult = { status ->
                if (status != 0) TrackingState.writeErrors.value += 1
            },
        )
        gatt = client
        client.connect(device)
        mainHandler.postDelayed(rssiPollRunnable, RSSI_POLL_INTERVAL_MS)
    }

    private fun onGattDisconnected() {
        // GATT dropped (camera went to standby, moved out of range, etc.).
        // Keep the service alive for a short grace window with the internal
        // LOW_LATENCY scan running — a quick off/on cycle is the common case
        // and we want to reconnect instantly. If nothing re-arrives within
        // the window, stopSelf() and let the OS-offloaded scan handle the
        // next wake.
        mainHandler.removeCallbacks(rssiPollRunnable)
        gatt = null
        TrackingState.rssi.value = null
        TrackingState.sessionStartedAt.value = null
        markContact()
        if (!TrackingState.serviceRunning.value) return
        startScan()
        mainHandler.removeCallbacks(reconnectGiveup)
        mainHandler.postDelayed(reconnectGiveup, RECONNECT_GRACE_MS)
    }

    /**
     * Fires after [RECONNECT_GRACE_MS] post-disconnect. If we still have no
     * GATT session, hand off to the offloaded system scan and exit: this
     * drops the notification and removes all CPU/BLE work from our process
     * while the OS wakes us only when the camera advertises awake again.
     *
     * Important: re-arm the system scan before exiting. The OS scan engine
     * tracks per-filter match state — if the camera stayed awake throughout
     * our session and grace window, the engine may still see it as "found"
     * and never emit another FIRST_MATCH edge. Unregister + register resets
     * that state so the next awake ad reliably wakes us.
     */
    private val reconnectGiveup = Runnable {
        if (gatt == null && TrackingState.serviceRunning.value) {
            val ctx = applicationContext
            if (Prefs.autoStartEnabled(ctx)) {
                TrackingState.log("reconnect grace elapsed → re-arm OS scan, hand off")
                CanonScanRegistrar.rearm(ctx)
                stopTracking()
            }
            // Auto-start off: stay running on the internal scan (will hit
            // INTERNAL_SCAN_MAX_MS eventually and exit cleanly via scanTimeout).
        }
    }

    /** Bumps the watchdog clock — called whenever we confirm the camera is reachable. */
    private fun markContact() {
        watchdogAnchorMs = SystemClock.elapsedRealtime()
    }

    /**
     * Bumps both clocks on real advertisement reception. `lastAdvertAt` is
     * rate-limited to 1s: the UI only renders rounded seconds, so sub-second
     * writes would trigger recompositions at scan rate (~6/s awake) with no
     * visible effect.
     */
    private fun markAdvertisement() {
        val now = SystemClock.elapsedRealtime()
        watchdogAnchorMs = now
        val prev = TrackingState.lastAdvertAt.value
        if (prev == null || now - prev >= 1000L) {
            TrackingState.lastAdvertAt.value = now
        }
    }

    // --- Location ---

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            TrackingState.log("ACCESS_FINE_LOCATION missing; cannot stream fixes")
            return
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(5000L)
            .build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        TrackingState.log("Location updates requested")
    }

    private fun onLocation(loc: Location) {
        val client = gatt ?: return
        if (client.gpsState != CanonGpsState.READY_TO_RECEIVE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSentAt < MIN_SEND_INTERVAL_MS) return
        lastSentAt = now

        val nowSec = System.currentTimeMillis() / 1000L
        TrackingState.log("→ sendGps lat=%.6f lon=%.6f alt=%.1f t=%d"
            .format(loc.latitude, loc.longitude,
                if (loc.hasAltitude()) loc.altitude else Double.NaN, nowSec))
        val ok = client.sendGps(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitude = if (loc.hasAltitude()) loc.altitude else Double.NaN,
            unixSeconds = nowSec,
        )
        if (ok) {
            TrackingState.fixCount.value += 1
            TrackingState.lastFixText.value =
                "%.6f, %.6f".format(loc.latitude, loc.longitude)
            TrackingState.lastFixAt.value = SystemClock.elapsedRealtime()
            updateNotification()
        }
    }

    // --- Helpers ---


    private fun createNotificationChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "EOS GPS Tracking",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Live GPS forwarding to Canon EOS"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GpsTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EOS GPS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val power = TrackingState.cameraPower.value
        val conn = TrackingState.connState.value
        val fixes = TrackingState.fixCount.value
        val last = TrackingState.lastFixText.value ?: "—"
        val text = "${power.label()} · $conn · $fixes fixes · $last"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "de.schaefer.eosgps.STOP"
        const val EXTRA_AWAKE_AD_HINT = "de.schaefer.eosgps.AWAKE_AD_HINT"

        fun start(ctx: Context) {
            val i = Intent(ctx, GpsTrackingService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }

        /**
         * Called by [ScanResultReceiver] after the OS-offloaded scan
         * observed an awake-shaped advertisement. The service skips its
         * own scan and jumps straight to GATT when this hint is set.
         */
        fun startForAwakeAd(ctx: Context) {
            val i = Intent(ctx, GpsTrackingService::class.java)
                .putExtra(EXTRA_AWAKE_AD_HINT, true)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, GpsTrackingService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}

