package de.schaefer.eosgps

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "CanonScanReg"

/**
 * Registers a system-offloaded BLE scan with the OS so that the Canon camera's
 * awake-advertisement wakes our app without us holding a foreground service.
 *
 * The filter matches any device whose Canon-manufacturer-data (company id
 * `0x01A9`) byte 5 is an "awake"-shaped value (low 3 bits = 0b010 = `0x02`).
 * Asleep advertisements (byte 5 = `0x05`, low 3 bits = `0b101`) are filtered
 * out in hardware and never wake the app.
 *
 * MAC is intentionally NOT part of the hardware filter. The only public
 * `ScanFilter.setDeviceAddress(String)` variant is picky about implicit
 * address-type guessing (Phase 6 lesson in `GpsTrackingService.kt:217-219`);
 * the 2-argument `setDeviceAddress(String, int)` that would fix it is
 * `@SystemApi` and only available to privileged apps. We tolerate that
 * foreign Canon cameras in range can trigger the receiver — they're rare,
 * broadcasts are cheap, and [ScanResultReceiver] re-verifies MAC in code
 * before doing any real work.
 *
 * The scan callback is a `PendingIntent` aimed at [ScanResultReceiver]; when
 * the BT stack detects a match it broadcasts the scan result, Android spins up
 * our process (if needed) and delivers it to the receiver — no foreground
 * service required to stay alive in between.
 */
object CanonScanRegistrar {

    private const val ACTION_MATCH = "de.schaefer.eosgps.SCAN_MATCH"
    private const val PI_REQUEST_CODE = 0xCA0

    /**
     * Registers a PendingIntent-based scan with the OS for the bonded Canon
     * camera. Safe to call repeatedly — the OS deduplicates identical
     * PendingIntents; we explicitly unregister first to cover filter changes.
     * Returns true if registration succeeded.
     */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context): Boolean {
        if (!hasBluetoothPermissions(ctx)) {
            Log.w(TAG, "register: missing BLUETOOTH_SCAN/CONNECT permission")
            return false
        }
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "register: bluetooth adapter unavailable or disabled")
            return false
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "register: bluetoothLeScanner null")
            return false
        }
        // We still need a bonded Canon on file — the receiver filters the
        // MAC and we don't want to arm a scan if the user has no camera
        // paired yet.
        val mac = findBondedCanonMac(adapter) ?: run {
            Log.w(TAG, "register: no bonded EOS device found")
            return false
        }

        val filter = ScanFilter.Builder()
            .setManufacturerData(CanonAd.COMPANY_ID, CanonAd.AWAKE_MFG_DATA, CanonAd.AWAKE_MFG_MASK)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()

        // Unregister first to clear any stale registration with different
        // filters from a previous app version.
        try { scanner.stopScan(buildPendingIntent(ctx)) } catch (_: Exception) {}

        val pi = buildPendingIntent(ctx)
        return try {
            val rc = scanner.startScan(listOf(filter), settings, pi)
            if (rc == 0) {
                Prefs.setScanRegistered(ctx, true)
                TrackingState.scanRegistered.value = true
                Log.i(TAG, "register: scan armed (byte5 low3=010; target MAC $mac verified in receiver)")
                true
            } else {
                Log.w(TAG, "register: startScan returned $rc")
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "register: SecurityException", e)
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "register: IllegalStateException", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun unregister(ctx: Context) {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = mgr?.adapter?.bluetoothLeScanner
        if (scanner != null && hasBluetoothPermissions(ctx)) {
            try {
                scanner.stopScan(buildPendingIntent(ctx))
                Log.i(TAG, "unregister: scan disarmed")
            } catch (e: Exception) {
                Log.w(TAG, "unregister: ${e.message}")
            }
        }
        Prefs.setScanRegistered(ctx, false)
        TrackingState.scanRegistered.value = false
    }

    private fun buildPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ScanResultReceiver::class.java).setAction(ACTION_MATCH)
        // FLAG_MUTABLE required on API 31+ because the BT stack fills the
        // intent extras with the ScanResult before delivery.
        return PendingIntent.getBroadcast(
            ctx,
            PI_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    private fun findBondedCanonMac(adapter: BluetoothAdapter): String? =
        adapter.bondedDevices.firstOrNull { (it.name ?: "").contains("EOS", true) }?.address

    private fun hasBluetoothPermissions(ctx: Context): Boolean {
        val scan = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
        val connect = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        return scan == PackageManager.PERMISSION_GRANTED &&
            connect == PackageManager.PERMISSION_GRANTED
    }
}
