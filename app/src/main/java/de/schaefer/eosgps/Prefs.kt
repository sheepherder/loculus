package de.schaefer.eosgps

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences wrapper. One flag: `autoStartEnabled` — the user opted
 * in to offloaded background scanning. `BootReceiver` re-arms the scan on
 * boot and app updates when set.
 */
object Prefs {
    private const val NAME = "eos_gps_prefs"
    private const val KEY_AUTO_START = "auto_start_enabled"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autoStartEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_START, false)

    fun setAutoStartEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_START, value).apply()
    }
}
