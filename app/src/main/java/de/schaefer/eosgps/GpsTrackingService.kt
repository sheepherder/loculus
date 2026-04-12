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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
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
private const val MIN_SEND_INTERVAL_MS = 10_000L  // rate-limit BLE writes (conservative)

class GpsTrackingService : Service() {

    private var gatt: CanonGattClient? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastSentAt = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            onLocation(loc)
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

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (TrackingState.serviceRunning.value) return

        val notif = buildNotification("Starting…")
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, types)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        TrackingState.serviceRunning.value = true

        val device = findBondedCanon()
        if (device == null) {
            TrackingState.log("No bonded EOS device found")
            stopSelf()
            return
        }

        val client = CanonGattClient(
            context = applicationContext,
            onLog = { TrackingState.log(it) },
            onStateChange = {
                TrackingState.connState.value = it
                updateNotification()
            },
            onGpsState = {
                TrackingState.gpsState.value = it
                updateNotification()
            },
        )
        gatt = client
        client.connect(device)

        startLocationUpdates()
    }

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

        // Always use wall-clock seconds so the timestamp advances monotonically
        // even when FusedLocationProvider reuses a cached Location.
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
            updateNotification()
        }
    }

    private fun stopTracking() {
        Log.i(TAG, "stopTracking")
        fused.removeLocationUpdates(locationCallback)
        gatt?.stopAndDisconnect()
        gatt = null
        TrackingState.serviceRunning.value = false
        TrackingState.connState.value = ConnState.IDLE
        TrackingState.gpsState.value = CanonGpsState.UNKNOWN
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        gatt?.stopAndDisconnect()
        gatt = null
        TrackingState.serviceRunning.value = false
        super.onDestroy()
    }

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
        val conn = TrackingState.connState.value
        val gps = TrackingState.gpsState.value
        val fixes = TrackingState.fixCount.value
        val last = TrackingState.lastFixText.value ?: "—"
        val text = "$conn · gps=$gps · $fixes fixes · $last"
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
