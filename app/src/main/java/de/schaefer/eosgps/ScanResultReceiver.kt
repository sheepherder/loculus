package de.schaefer.eosgps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
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
 * A subtle Android quirk: PendingIntent-based offloaded scans deliver
 * ScanResults with a trimmed-down [ScanResult.getScanRecord] — often null.
 * So we trust the HW filter for the awake-byte match (the broadcast only
 * fires when the chip confirmed it) and only filter MAC here, because
 * [ScanResult.getDevice] always carries the address.
 */
class ScanResultReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        val errCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (errCode != -1) {
            // Scan failed at the OS layer. Common causes: BT toggled off, app
            // moved into a restricted standby bucket. We log and move on; we
            // will try to re-register next time the user opens the app or the
            // phone reboots.
            Log.w(TAG, "scan error $errCode; clearing registration")
            Prefs.setScanRegistered(ctx, false)
            TrackingState.scanRegistered.value = false
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

        val bondedMac = bondedCanonMac(ctx) ?: return

        val match = results.firstOrNull { r ->
            r.device?.address?.equals(bondedMac, ignoreCase = true) == true
        } ?: run {
            Log.d(TAG, "no result for $bondedMac in ${results.size} result(s)")
            return
        }

        Log.i(TAG, "awake ad from $bondedMac rssi=${match.rssi} → waking service")
        GpsTrackingService.startForAwakeAd(ctx)
    }

    @SuppressLint("MissingPermission")
    private fun bondedCanonMac(ctx: Context): String? {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: return null
        return try {
            adapter.bondedDevices.firstOrNull { (it.name ?: "").contains("EOS", true) }?.address
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedCanonMac: ${e.message}")
            null
        }
    }
}
