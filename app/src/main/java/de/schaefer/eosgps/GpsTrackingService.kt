package de.schaefer.eosgps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
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

// Canon BLE advertisement decoding. Company ID 0x01A9 is Murata (the OEM whose
// module Canon uses). Android's getManufacturerSpecificData returns 6 bytes
// after the company-id prefix — e.g. "01 0b 33 f3 4b 05" on our R6m2. The
// first 5 bytes are constant model/variant padding; the last byte (index 5)
// is the power state, verified by HCI snoops of both camera states:
//   0x02 = camera fully on (safe to connect + kickoff)
//   0x05 = camera in BLE standby (connecting + writing would wake it)
private const val CANON_COMPANY_ID = 0x01A9
private const val STATE_AWAKE: Byte = 0x02
private const val STATE_ASLEEP: Byte = 0x05
private const val POWER_BYTE_INDEX = 5

class GpsTrackingService : Service() {

    private var gatt: CanonGattClient? = null
    private var bondedDevice: BluetoothDevice? = null
    private var scanner: BluetoothLeScanner? = null
    @Volatile private var scanning = false
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastSentAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val rssiPollRunnable = object : Runnable {
        override fun run() {
            gatt?.readRssi()
            mainHandler.postDelayed(this, RSSI_POLL_INTERVAL_MS)
        }
    }

    // Flip cameraPower to UNSEEN when we go too long without an advertisement
    // while not connected — covers battery-pull, out-of-range and physical-off
    // scenarios where neither a BLE disconnect nor a new ad arrives. Skipped
    // while a GATT session is active (the camera doesn't advertise then, so
    // stale lastAdvertAt is expected).
    private val staleWatchdog = object : Runnable {
        override fun run() {
            val lastAt = TrackingState.lastAdvertAt.value
            val cur = TrackingState.cameraPower.value
            if (gatt == null && lastAt != null && cur != CameraPowerState.UNSEEN) {
                val elapsed = SystemClock.elapsedRealtime() - lastAt
                if (elapsed > AD_STALENESS_THRESHOLD_MS) {
                    TrackingState.log("no adv for ${elapsed / 1000}s → unseen")
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
            Log.d(TAG, "scan: ${result.device.address} mfg=${result.scanRecord?.manufacturerSpecificData}")
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
            else -> startTracking()
        }
        return START_STICKY
    }

    // --- Lifecycle ---

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (TrackingState.serviceRunning.value) return

        val notif = buildNotification("Scanning for camera…")
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, types)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        TrackingState.serviceRunning.value = true
        TrackingState.resetSession()

        val device = findBondedCanon()
        if (device == null) {
            TrackingState.log("No bonded EOS device found")
            stopSelf()
            return
        }
        bondedDevice = device

        startLocationUpdates()
        startScan()
        mainHandler.postDelayed(staleWatchdog, STALENESS_CHECK_INTERVAL_MS)
    }

    private fun stopTracking() {
        Log.i(TAG, "stopTracking")
        mainHandler.removeCallbacks(rssiPollRunnable)
        mainHandler.removeCallbacks(staleWatchdog)
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
        // Scan without a ScanFilter for now: Android's ScanFilter.setDeviceAddress
        // is sometimes picky about address type (Public vs Random) and can silently
        // suppress all callbacks. We filter by MAC in handleAdvertisement instead.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            s.startScan(emptyList(), settings, scanCallback)
            scanning = true
            TrackingState.log("→ scanning (unfiltered, matching ${device.address} in code)")
            Log.i(TAG, "startScan started for ${device.address}")
        } catch (e: SecurityException) {
            TrackingState.log("scan perm denied: ${e.message}")
            Log.e(TAG, "scan perm denied", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    private fun handleAdvertisement(result: ScanResult) {
        val target = bondedDevice?.address ?: return
        if (!result.device.address.equals(target, ignoreCase = true)) return
        val mfg = result.scanRecord?.getManufacturerSpecificData(CANON_COMPANY_ID) ?: return
        if (mfg.size <= POWER_BYTE_INDEX) return
        TrackingState.lastAdvertAt.value = SystemClock.elapsedRealtime()
        val powerByte = mfg[POWER_BYTE_INDEX]
        val newState = when (powerByte) {
            STATE_AWAKE -> CameraPowerState.AWAKE
            STATE_ASLEEP -> CameraPowerState.ASLEEP
            else -> CameraPowerState.ASLEEP  // unknown → treat as "don't wake"
        }
        val prev = TrackingState.cameraPower.value
        if (prev != newState) {
            TrackingState.cameraPower.value = newState
            TrackingState.log("adv: ${prev.name.lowercase()} → ${newState.name.lowercase()} (${mfg.toHex()})")
        }
        if (newState == CameraPowerState.AWAKE && gatt == null) {
            // Camera just became reachable in awake state. Stop scanning and
            // establish GATT + fire Canon kickoff.
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
        // Release the client and return to advertisement scanning so we can
        // auto-reconnect when the camera next flips to awake.
        mainHandler.removeCallbacks(rssiPollRunnable)
        gatt = null
        TrackingState.rssi.value = null
        TrackingState.sessionStartedAt.value = null
        // Refresh the staleness clock: we were in contact via GATT right up
        // to this moment, so the watchdog shouldn't treat the pre-connection
        // lastAdvertAt (which can be minutes old) as evidence of silence.
        TrackingState.lastAdvertAt.value = SystemClock.elapsedRealtime()
        if (TrackingState.serviceRunning.value) startScan()
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

    @SuppressLint("MissingPermission")
    private fun findBondedCanon(): BluetoothDevice? {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = mgr.adapter ?: return null
        return adapter.bondedDevices.firstOrNull { (it.name ?: "").contains("EOS", true) }
    }

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
        val text = "${power.name.lowercase()} · $conn · $fixes fixes · $last"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "de.schaefer.eosgps.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, GpsTrackingService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, GpsTrackingService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
