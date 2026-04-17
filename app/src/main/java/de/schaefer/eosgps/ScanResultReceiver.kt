package de.schaefer.eosgps

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val TAG = "ScanResultReceiver"

/**
 * Wakes up when the OS's offloaded scan matches our filter (Canon
 * manufacturer-data with byte 5 low-3-bits = `0b010` = awake). Registered
 * via [CanonScanRegistrar.register].
 *
 * PendingIntent-based offloaded scans deliver ScanResults with a trimmed-down
 * [ScanResult.getScanRecord] — often null. We trust the HW filter for the
 * awake-byte match (the broadcast only fires when the chip confirmed it)
 * and only filter MAC here, because [ScanResult.getDevice] always carries
 * the address.
 */
class ScanResultReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
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

        val bondedMac = findBondedCanon(ctx)?.address ?: return

        val match = results.firstOrNull { r ->
            r.device?.address?.equals(bondedMac, ignoreCase = true) == true
        } ?: run {
            Log.d(TAG, "no result for $bondedMac in ${results.size} result(s)")
            return
        }

        Log.i(TAG, "awake ad from $bondedMac rssi=${match.rssi} → waking service")
        GpsTrackingService.startForAwakeAd(ctx)
    }
}
