package com.xmiqarri.cardisplayz

import android.content.Context

/** Settings the user changes on the settings screen. Stored on the device only. */
object AppSettings {
    private const val PREFS = "car_displayz_settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun floatingEnabled(context: Context): Boolean = prefs(context).getBoolean("floating_enabled", true)

    fun setFloatingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("floating_enabled", enabled).apply()
    }

    fun startOnBoot(context: Context): Boolean = prefs(context).getBoolean("start_on_boot", true)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("start_on_boot", enabled).apply()
    }

    /** Bottom bar with Back, Home, Recents and memory cleanup. Shows only when the accessibility service is on. */
    fun navBarEnabled(context: Context): Boolean = prefs(context).getBoolean("navbar_enabled", true)

    fun setNavBarEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("navbar_enabled", enabled).apply()
    }

    fun autoUpdate(context: Context): Boolean = prefs(context).getBoolean("auto_update", true)

    fun setAutoUpdate(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_update", enabled).apply()
    }

    /** Saves the outcome of the last update check: "none", "available" or "error". */
    fun recordCheck(context: Context, state: String, version: String?) {
        prefs(context).edit()
            .putLong("last_check_at", System.currentTimeMillis())
            .putString("last_check_state", state)
            .putString("last_check_version", version ?: "")
            .apply()
    }

    fun lastCheckAt(context: Context): Long = prefs(context).getLong("last_check_at", 0L)

    fun lastCheckState(context: Context): String = prefs(context).getString("last_check_state", "") ?: ""

    fun lastCheckVersion(context: Context): String = prefs(context).getString("last_check_version", "") ?: ""
}
