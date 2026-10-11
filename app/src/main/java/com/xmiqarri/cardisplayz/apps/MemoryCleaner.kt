package com.xmiqarri.cardisplayz.apps

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Stops the background processes of apps the user installed, which frees memory for the car system.
 * Android does not allow removing apps that are on screen this way; those are closed with Back or
 * the recent-apps "Close All" button. System apps that came with the car are never touched.
 */
object MemoryCleaner {

    /** @return how many apps were asked to stop. */
    fun clean(context: Context): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = context.packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }
            .filter { it != context.packageName }
            .distinct()

        packages.forEach { activityManager.killBackgroundProcesses(it) }
        return packages.size
    }
}
