package de.schaefer.eosgps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "BootReceiver"

/**
 * Re-arms the offloaded system scan after device boot or app update. Does not
 * start a foreground service on its own — we wait until the scan delivers an
 * awake advertisement, at which point [ScanResultReceiver] starts the service.
 *
 * Receives: BOOT_COMPLETED (default), LOCKED_BOOT_COMPLETED (direct-boot,
 * before unlock — harmless no-op since we aren't direct-boot-aware and the
 * scan registration is rebuilt via BOOT_COMPLETED anyway), MY_PACKAGE_REPLACED
 * (so the scan is re-armed after app updates that change the filter or the
 * receiver class signature).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "onReceive action=$action")
        if (!Prefs.autoStartEnabled(ctx)) {
            Log.i(TAG, "auto-start disabled; no-op")
            return
        }
        // Registration is cheap; always re-register on these events rather
        // than trusting whatever state we held before reboot.
        val ok = CanonScanRegistrar.register(ctx)
        Log.i(TAG, "re-armed scan: $ok")
    }
}
