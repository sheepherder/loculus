package de.schaefer.eosgps

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

        val results: List<ScanResult> = intent.getParcelableArrayListExtra(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java
        ) ?: emptyList()

        if (results.isEmpty()) return
        // A PendingIntent that was already queued by the BT stack can still
        // be delivered after the user toggled tracking off (and we called
        // stopScan). Drop it so we never connect against the user's intent.
        if (!Prefs.trackingEnabled(ctx)) {
            Log.i(TAG, "tracking disabled — dropping late scan result")
            return
        }
        val first = results.first()
        val expectedMac = Prefs.selectedDeviceMac(ctx)
        if (expectedMac != null && first.device?.address != expectedMac) {
            Log.i(TAG, "scan result MAC ${first.device?.address} ≠ selected $expectedMac — dropping")
            return
        }
        Log.i(TAG, "awake ad from ${first.device?.address} rssi=${first.rssi} → waking service")
        GpsTrackingService.start(ctx)
    }
}
