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

    /** Display id of the rear screen, or -1 when none is chosen. */
    fun rearDisplayId(context: Context): Int = prefs(context).getInt("rear_display_id", -1)

    fun setRearDisplayId(context: Context, id: Int) {
        prefs(context).edit().putInt("rear_display_id", id).apply()
    }

    /** When on, tapping an app opens it on the rear screen instead of this one. */
    fun launchOnRear(context: Context): Boolean = prefs(context).getBoolean("launch_on_rear", false)

    fun setLaunchOnRear(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("launch_on_rear", on).apply()
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

    /** Opacity of the floating button, in percent (20..100). */
    fun floatingAlpha(context: Context): Int = prefs(context).getInt("floating_alpha", 50)

    fun setFloatingAlpha(context: Context, percent: Int) {
        prefs(context).edit().putInt("floating_alpha", percent).apply()
    }

    /** Diameter of the floating button in dp (30..72). */
    fun floatingSizeDp(context: Context): Int = prefs(context).getInt("floating_size_dp", 40)

    fun setFloatingSizeDp(context: Context, dp: Int) {
        prefs(context).edit().putInt("floating_size_dp", dp).apply()
    }

    /** Diameter of each top button in dp (36..72). The labels scale with it. */
    fun navSizeDp(context: Context): Int = prefs(context).getInt("nav_size_dp", 56)

    fun setNavSizeDp(context: Context, dp: Int) {
        prefs(context).edit().putInt("nav_size_dp", dp).apply()
    }

    /** Opacity of the top buttons in percent (20..100). */
    fun navOpacity(context: Context): Int = prefs(context).getInt("nav_opacity", 100)

    fun setNavOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt("nav_opacity", percent).apply()
    }

    /** Fade the top buttons after a few idle seconds. */
    fun navAutoHide(context: Context): Boolean = prefs(context).getBoolean("nav_autohide", false)

    fun setNavAutoHide(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("nav_autohide", enabled).apply()
    }

    /** Last position of the top buttons in pixels, or null when the user has not moved them yet. */
    fun navPosition(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        if (!p.contains("nav_x")) return null
        return p.getInt("nav_x", 0) to p.getInt("nav_y", 0)
    }

    fun setNavPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt("nav_x", x).putInt("nav_y", y).apply()
    }

    /** Shows the Delayed lock button in the app grid. */
    fun delayedLockEnabled(context: Context): Boolean = prefs(context).getBoolean("delayed_lock_enabled", false)

    fun setDelayedLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("delayed_lock_enabled", enabled).apply()
    }

    /** Locks the doors when the car refuses a lock from the key while it is on. Off by default. */
    fun fobLockAssist(context: Context): Boolean = prefs(context).getBoolean("fob_lock_assist", false)

    fun setFobLockAssist(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("fob_lock_assist", enabled).apply()
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
