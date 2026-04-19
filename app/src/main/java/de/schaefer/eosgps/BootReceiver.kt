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
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "onReceive action=$action")
        if (!Prefs.trackingEnabled(ctx)) {
            Log.i(TAG, "tracking disabled; no-op")
            return
        }
        resolveSelectedDevice(ctx)
        val ok = CanonScanRegistrar.register(ctx)
        Log.i(TAG, "re-armed scan: $ok")
    }
}
