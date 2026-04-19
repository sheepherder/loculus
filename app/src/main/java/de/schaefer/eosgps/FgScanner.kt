package de.schaefer.eosgps

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

private const val TAG = "FgScanner"

/**
 * Foreground live scanner, owned by [MainActivity]'s `onResume`/`onPause`
 * lifecycle. Streams Canon advertisements into [TrackingState] so the UI can
 * render the live power state, and — when tracking is enabled — triggers the
 * [GpsTrackingService] as soon as the camera advertises POWER_ON.
 *
 * Hardware filter matches our bonded Canon (public MAC, Murata OUI) plus the
 * Canon manufacturer company id (0x01A9). No byte-5 constraint: the UI wants
 * to see all three power states (on / auto-off / sw-off) live, so decoding
 * happens in software. SCAN_MODE_LOW_LATENCY because we want sub-second
 * feedback when the user watches the screen.
 *
 * Android silently downgrades a direct-ScanCallback scan to
 * SCAN_MODE_OPPORTUNISTIC after ~30 minutes with no API to detect it; we
 * pre-emptively stop+start every 20 minutes to avoid drifting into that
 * stale-and-invisible state.
 */
@SuppressLint("MissingPermission")
object FgScanner {

    private const val RESTART_INTERVAL_MS = 20 * 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    @Volatile private var running = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handle(r)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "onScanFailed code=$errorCode")
            running = false
        }
    }

    private val restartRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            Log.i(TAG, "20-min restart (defeat silent Android throttling)")
            stopScanInternal()
            val ctx = lastContext ?: return
            startScanInternal(ctx)
            mainHandler.postDelayed(this, RESTART_INTERVAL_MS)
        }
    }

    private var lastContext: Context? = null

    fun start(ctx: Context) {
        if (running) return
        lastContext = ctx.applicationContext
        // Defensive: an earlier failed-then-restarted cycle could have left a
        // pending restart scheduled. Cancel before re-scheduling so we never
        // end up with two parallel 20-min loops.
        mainHandler.removeCallbacks(restartRunnable)
        startScanInternal(ctx)
        mainHandler.postDelayed(restartRunnable, RESTART_INTERVAL_MS)
    }

    fun stop() {
        mainHandler.removeCallbacks(restartRunnable)
        stopScanInternal()
        lastContext = null
    }

    private fun startScanInternal(ctx: Context) {
        val s = readyScanner(ctx) ?: run {
            Log.w(TAG, "scanner preflight failed")
            return
        }
        val target = findSelectedDevice(ctx) ?: run {
            Log.w(TAG, "no bonded EOS device")
            return
        }
        scanner = s
        val filter = ScanFilter.Builder()
            .setDeviceAddress(target.address)
            .setManufacturerData(CanonAd.COMPANY_ID, ByteArray(0), ByteArray(0))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            s.startScan(listOf(filter), settings, callback)
            running = true
            Log.i(TAG, "scanning ${target.address}")
        } catch (e: SecurityException) {
            Log.e(TAG, "scan perm denied", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startScan failed", e)
        }
    }

    private fun stopScanInternal() {
        if (!running) return
        try { scanner?.stopScan(callback) } catch (_: Exception) {}
        running = false
    }

    private fun handle(result: ScanResult) {
        val mfg = result.scanRecord?.getManufacturerSpecificData(CanonAd.COMPANY_ID) ?: return
        if (mfg.size <= CanonAd.POWER_BYTE_INDEX) return
        val now = SystemClock.elapsedRealtime()
        val prevAdv = TrackingState.lastAdvertAt.value
        if (prevAdv == null || now - prevAdv >= 1000L) {
            TrackingState.lastAdvertAt.value = now
        }
        // Only expose RSSI from ads while there is no active GATT session —
        // during a session the camera stops advertising and the GATT-side
        // RSSI poll (CanonGattClient.readRssi) is the authoritative source.
        if (!TrackingState.serviceRunning.value) {
            TrackingState.rssi.value = result.rssi
        }
        val newState = CanonAd.powerStateFromByte(mfg[CanonAd.POWER_BYTE_INDEX]) ?: return
        if (TrackingState.cameraPower.value != newState) {
            TrackingState.cameraPower.value = newState
        }
        if (newState == CameraPowerState.POWER_ON &&
            !TrackingState.serviceRunning.value) {
            val ctx = lastContext ?: return
            if (Prefs.trackingEnabled(ctx)) {
                Log.i(TAG, "POWER_ON + tracking on → start FGS")
                GpsTrackingService.start(ctx)
            }
        }
    }
}
