package de.schaefer.eosgps

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences wrapper. One flag: `trackingEnabled` — passive behavior
 * switch. When on: OS-offloaded scan armed while the app is not in the
 * foreground; on a POWER_ON advertisement the FGS wakes up and streams GPS.
 * `BootReceiver` re-arms the scan on boot and app updates.
 *
 * The key was previously named `auto_start_enabled`; on first read we migrate
 * the old value into the new key and drop the old one so existing installs
 * don't silently lose their opt-in.
 */
internal const val APP_NAME = "Loculus"

object Prefs {
    private const val NAME = "eos_gps_prefs"
    private const val KEY_TRACKING = "tracking_enabled"
    private const val KEY_DEVICE_MAC = "selected_device_mac"
    private const val LEGACY_KEY_AUTO_START = "auto_start_enabled"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun trackingEnabled(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.contains(KEY_TRACKING) && p.contains(LEGACY_KEY_AUTO_START)) {
            val legacy = p.getBoolean(LEGACY_KEY_AUTO_START, false)
            p.edit().putBoolean(KEY_TRACKING, legacy).remove(LEGACY_KEY_AUTO_START).apply()
            return legacy
        }
        return p.getBoolean(KEY_TRACKING, true)
    }

    fun setTrackingEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TRACKING, value).apply()
    }

    fun selectedDeviceMac(ctx: Context): String? =
        prefs(ctx).getString(KEY_DEVICE_MAC, null)

    fun setSelectedDeviceMac(ctx: Context, mac: String?) {
        prefs(ctx).edit().putString(KEY_DEVICE_MAC, mac).apply()
    }
}
