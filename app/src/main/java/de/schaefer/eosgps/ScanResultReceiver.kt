package de.schaefer.eosgps

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val TAG = "ScanResultReceiver"

/**
 * Wakes up when the OS's offloaded scan matches our filter (bonded Canon MAC
 * + manufacturer-data byte 5 low-3-bits = `0b010` = awake). Registered via
 * [CanonScanRegistrar.register]. Filter is strict enough that any broadcast
 * arriving here means "our camera is awake" — no further checks needed.
 */
class ScanResultReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val errCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (errCode != -1) {
            Log.w(TAG, "scan error $errCode")
            return
        }

        val results: List<ScanResult> = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        } ?: emptyList()

        if (results.isEmpty()) return
        val first = results.first()
        Log.i(TAG, "awake ad from ${first.device?.address} rssi=${first.rssi} → waking service")
        GpsTrackingService.startForAwakeAd(ctx)
    }
}
