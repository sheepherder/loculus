package de.schaefer.eosgps

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "CanonScanReg"

/**
 * Registers a system-offloaded BLE scan with the OS so that the Canon camera's
 * awake-advertisement wakes our app without us holding a foreground service.
 *
 * The filter matches only our bonded Canon (public MAC, Murata OUI) AND
 * byte 5 of the Canon-manufacturer-data (company id `0x01A9`) being
 * "awake"-shaped (low 3 bits = 0b010 = `0x02`). Asleep ads, foreign Canon
 * cameras, and every other BLE device get dropped in hardware.
 */
object CanonScanRegistrar {

    private const val PI_REQUEST_CODE = 0xCA0

    @SuppressLint("MissingPermission")
    fun register(ctx: Context): Boolean {
        val scanner = readyScanner(ctx) ?: run {
            Log.w(TAG, "register: scanner preflight failed")
            return false
        }
        val target = findSelectedDevice(ctx) ?: run {
            Log.w(TAG, "register: no bonded EOS device found")
            return false
        }

        val filter = CanonAd.scanFilter(target.address, awakeOnly = true)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()

        val pi = buildPendingIntent(ctx)
        try { scanner.stopScan(pi) } catch (_: Exception) {}
        return try {
            val rc = scanner.startScan(listOf(filter), settings, pi)
            if (rc == 0) {
                Log.i(TAG, "register: scan armed (byte5 low3=010; target MAC ${target.address} verified in receiver)")
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
        val scanner = readyScanner(ctx) ?: return
        try {
            scanner.stopScan(buildPendingIntent(ctx))
            Log.i(TAG, "unregister: scan disarmed")
        } catch (e: Exception) {
            Log.w(TAG, "unregister: ${e.message}")
        }
    }

    /**
     * Reset the OS scan-engine's per-filter edge state. After a long-lived
     * GATT session the engine may still see the camera as "found", never
     * emitting another FIRST_MATCH. Unregister + register clears that.
     */
    fun rearm(ctx: Context) {
        unregister(ctx)
        register(ctx)
    }

    private fun buildPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ScanResultReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx,
            PI_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
