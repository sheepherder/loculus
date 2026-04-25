package de.schaefer.eosgps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
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
private const val RSSI_POLL_INTERVAL_MS = 500L

internal const val GPS_INTERVAL_MS = 10_000L
internal const val GPS_FASTEST_INTERVAL_MS = 5_000L
internal const val INITIAL_FIX_MAX_AGE_MS = 60_000L

/**
 * Foreground service owning the GATT session to the Canon camera. Lifetime
 * is scoped exactly to that session: start → connect → stream GPS fixes →
 * disconnect → stop. No scanning, no watchdogs, no reconnect grace. We only
 * get here when one of the two scanners (Activity-owned [FgScanner] or the
 * OS-offloaded [CanonScanRegistrar]) just saw a POWER_ON advertisement, so
 * we dive straight into GATT.
 *
 * On [onDestroy] we re-arm the OS-offloaded scan iff tracking is still on
 * and the Activity isn't visible — that way the next wake of the camera
 * reliably pings us, without having to care whether the system scanner's
 * edge state is stale from the session we just closed.
 */
class GpsTrackingService : Service() {

    private var gatt: CanonGattClient? = null
    private var bondedDevice: BluetoothDevice? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var notifMgr: NotificationManager
    private lateinit var tapIntent: PendingIntent
    private val rssiPollRunnable = object : Runnable {
        override fun run() {
            gatt?.readRssi()
            mainHandler.postDelayed(this, RSSI_POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        notifMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_SHUTTER -> {
                val ok = gatt?.triggerShutter() ?: false
                Log.i(TAG, "shutter trigger requested, accepted=$ok")
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        // NOT_STICKY — the offloaded scan (if tracking is on) re-wakes us on
        // the next POWER_ON advertisement, so we don't want Android to
        // auto-restart us out of a dead state.
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (TrackingState.serviceRunning.value) return
        // Hard gate: an OS-offloaded scan PendingIntent can be mid-flight
        // when the user flips tracking off. Without this check the service
        // would still connect to the camera after the user said "stop".
        if (!Prefs.trackingEnabled(this)) {
            Log.i(TAG, "tracking disabled — ignoring start")
            stopSelf()
            return
        }

        val notif = buildNotification("Scanning for camera…")
        // FGS type selection on Android 14+:
        //   - `connectedDevice` is always safe: we target a bonded BT device.
        //   - `location` additionally unlocks FusedLocationProvider while
        //     the Activity is in the background, but the platform only lets
        //     us declare it when we hold ACCESS_BACKGROUND_LOCATION as an
        //     exemption. Without it, location updates only flow while the
        //     Activity is visible.
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasBackgroundLocation(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        startForeground(NOTIF_ID, notif, types)
        TrackingState.serviceRunning.value = true
        TrackingState.resetSession()

        val device = findSelectedDevice(this)
        if (device == null) {
            Log.w(TAG, "no bonded EOS device found")
            stopSelf()
            return
        }
        bondedDevice = device

        TrackingState.cameraPower.value = CameraPowerState.POWER_ON
        TrackingState.lastAdvertAt.value = SystemClock.elapsedRealtime()

        activeService = this
        startLocationUpdates()
        startGattSession()
    }

    private fun stopTracking() {
        if (!TrackingState.serviceRunning.value) return
        TrackingState.serviceRunning.value = false
        Log.i(TAG, "stopTracking")
        mainHandler.removeCallbacks(rssiPollRunnable)
        fused.removeLocationUpdates(locationCallback)
        activeService = null
        gatt?.sendStopAndClose()
        gatt = null
        bondedDevice = null
        TrackingState.connState.value = ConnState.IDLE
        TrackingState.gpsState.value = CanonGpsState.UNKNOWN
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(rssiPollRunnable)
        fused.removeLocationUpdates(locationCallback)
        if (activeService === this) activeService = null
        gatt?.sendStopAndClose()
        gatt = null
        TrackingState.serviceRunning.value = false
        TrackingState.connState.value = ConnState.IDLE
        TrackingState.gpsState.value = CanonGpsState.UNKNOWN

        // Rearm (unregister+register) rather than trusting the OS scan
        // engine's edge state: after a long GATT session it may still see
        // the camera as "found" and never fire a fresh FIRST_MATCH.
        val ctx = applicationContext
        if (Prefs.trackingEnabled(ctx) && !TrackingState.appVisible) {
            CanonScanRegistrar.rearm(ctx)
        }
        super.onDestroy()
    }

    // --- GATT session ---

    @SuppressLint("MissingPermission")
    private fun startGattSession() {
        val device = bondedDevice ?: return
        if (gatt != null) return
        Log.i(TAG, "camera awake → connecting GATT")
        val client = CanonGattClient(
            context = applicationContext,
            onStateChange = {
                TrackingState.connState.value = it
                updateNotification()
                if (it == ConnState.GPS_SESSION_ACTIVE) {
                    mainHandler.postDelayed(rssiPollRunnable, RSSI_POLL_INTERVAL_MS)
                }
                if (it == ConnState.IDLE) onGattDisconnected()
            },
            onGpsState = {
                TrackingState.gpsState.value = it
                if (it == CanonGpsState.READY_TO_RECEIVE) {
                    if (TrackingState.sessionStartedAt.value == null) {
                        TrackingState.sessionStartedAt.value = SystemClock.elapsedRealtime()
                    }
                    sendLastLocationIfFresh()
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
    }

    private fun onGattDisconnected() {
        mainHandler.removeCallbacks(rssiPollRunnable)
        val status = gatt?.lastDisconnectStatus ?: 0
        Log.i(TAG, "GATT disconnected status=0x${status.toString(16)}")
        gatt = null
        TrackingState.rssi.value = null
        TrackingState.sessionStartedAt.value = null
        stopTracking()
    }

    // --- Location ---

    @SuppressLint("MissingPermission")
    private fun sendLastLocationIfFresh() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc == null || TrackingState.fixCount.value > 0) return@addOnSuccessListener
            val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
            if (ageMs <= INITIAL_FIX_MAX_AGE_MS) {
                Log.i(TAG, "immediate fix from cache (age=${ageMs}ms)")
                onLocation(loc)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION missing; cannot stream fixes")
            return
        }
        // Primary interval 10 s matcht Canon-Camera-Connect (`i4.n.java:552`).
        // Fastest 5 s lässt Android uns Updates früher zustellen wenn eine
        // andere App parallel GPS anfordert.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
            .setMinUpdateIntervalMillis(GPS_FASTEST_INTERVAL_MS)
            .build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    internal fun onLocation(loc: Location) {
        val client = gatt ?: return
        if (client.gpsState != CanonGpsState.READY_TO_RECEIVE) return

        val nowSec = System.currentTimeMillis() / 1000L
        val ok = client.sendGps(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitude = if (loc.hasAltitude()) loc.altitude else Double.NaN,
            unixSeconds = nowSec,
        )
        if (ok) {
            TrackingState.fixCount.value += 1
            TrackingState.lastFixText.value =
                String.format(java.util.Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude)
            TrackingState.lastFixAt.value = SystemClock.elapsedRealtime()
            TrackingState.accuracy.value = if (loc.hasAccuracy()) loc.accuracy else null
            TrackingState.altitude.value = if (loc.hasAltitude()) loc.altitude else null
            TrackingState.speed.value = if (loc.hasSpeed()) loc.speed else null
            updateNotification()
        }
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "GPS-Übertragung",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Live GPS forwarding to Canon EOS"
            setShowBadge(false)
        }
        notifMgr.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(APP_NAME)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()

    private fun updateNotification() {
        val power = TrackingState.cameraPower.value
        val conn = TrackingState.connState.value
        val fixes = TrackingState.fixCount.value
        val last = TrackingState.lastFixText.value ?: "—"
        val text = "${power.label()} · $conn · $fixes fixes · $last"
        notifMgr.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "de.schaefer.eosgps.STOP"
        const val ACTION_SHUTTER = "de.schaefer.eosgps.SHUTTER"

        @Volatile
        private var activeService: GpsTrackingService? = null

        private val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val svc = activeService ?: return
                Log.i(TAG, "onLocationResult: ${result.locations.size} locs, " +
                        "cb=${hashCode()}, thread=${Thread.currentThread().name}")
                val loc = result.lastLocation ?: return
                svc.onLocation(loc)
            }
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, GpsTrackingService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, GpsTrackingService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }

        fun triggerShutter(ctx: Context) {
            val i = Intent(ctx, GpsTrackingService::class.java).setAction(ACTION_SHUTTER)
            ctx.startService(i)
        }
    }
}
