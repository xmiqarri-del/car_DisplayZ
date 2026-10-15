package com.xmiqarri.cardisplayz.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.xmiqarri.cardisplayz.adb.AdbGranter

/**
 * Force-stops the apps the user installed, so their memory goes back to the car system.
 * The stop is sent through ADB (the same connection used for permissions), because an app cannot
 * stop other apps on its own: Android only lets it end its own processes.
 * System apps that came with the car are never touched, and this app keeps running.
 */
object MemoryCleaner {

    /** @return how many apps were stopped, or null when ADB is not reachable. Blocking: run it off the main thread. */
    fun clean(context: Context): Int? {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = context.packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }
            .filter { it != context.packageName }
            .distinct()

        return AdbGranter.forceStop(context, packages)
    }
}
