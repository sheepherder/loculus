package de.schaefer.eosgps

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal SharedPreferences wrapper. Two flags drive the auto-start flow:
 *   autoStartEnabled — user opted in on onboarding; BootReceiver re-arms the
 *     system scan on device boot when true.
 *   scanRegistered   — we currently have an active PendingIntent scan
 *     registration with the OS. Survives process death; we use it to tell
 *     whether to unregister-before-register on app updates, and to surface
 *     a "background listening: on/off" state in the UI.
 *
 * No DataStore — two booleans don't justify the Flow plumbing.
 */
object Prefs {
    private const val NAME = "eos_gps_prefs"
    private const val KEY_AUTO_START = "auto_start_enabled"
    private const val KEY_SCAN_REGISTERED = "scan_registered"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autoStartEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_START, false)

    fun setAutoStartEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_START, value).apply()
    }

    fun scanRegistered(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SCAN_REGISTERED, false)

    fun setScanRegistered(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SCAN_REGISTERED, value).apply()
    }
}
