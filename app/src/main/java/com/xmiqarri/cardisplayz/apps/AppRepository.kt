package com.xmiqarri.cardisplayz.apps

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.view.Display
import org.json.JSONObject

/** One launchable app, with its usage statistics. [isSystem] apps come with the car and cannot be deleted. */
data class AppItem(
    val label: String,
    val packageName: String,
    val category: Int,
    val launches: Int,
    val lastUsed: Long,
    val icon: Drawable?,
    val isSystem: Boolean,
)

data class Usage(val count: Int, val time: Long)

/** Stores how often and when each app was opened. Kept on the device only. */
object AppUsage {
    private const val PREFS = "app_usage"
    private const val KEY = "usage_json"

    fun load(context: Context): Map<String, Usage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key ->
                val entry = json.getJSONObject(key)
                Usage(entry.optInt("c"), entry.optLong("t"))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun record(context: Context, packageName: String) {
        val all = load(context).toMutableMap()
        val previous = all[packageName]
        all[packageName] = Usage((previous?.count ?: 0) + 1, System.currentTimeMillis())

        val json = JSONObject()
        all.forEach { (pkg, usage) ->
            json.put(pkg, JSONObject().put("c", usage.count).put("t", usage.time))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.toString())
            .apply()
    }
}

object AppRepository {

    /** Launchable apps, most relevant first: frequent and recent apps rise to the top. */
    fun load(context: Context): List<AppItem> {
        val pm = context.packageManager
        val usage = AppUsage.load(context)
        val now = System.currentTimeMillis()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                val pkg = info.activityInfo.packageName
                val appInfo = info.activityInfo.applicationInfo
                val stat = usage[pkg]
                AppItem(
                    label = info.loadLabel(pm).toString(),
                    packageName = pkg,
                    category = appInfo.category,
                    launches = stat?.count ?: 0,
                    lastUsed = stat?.time ?: 0L,
                    icon = info.loadIcon(pm),
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareByDescending<AppItem> { score(it, now) }
                    .thenBy { it.label.lowercase() }
            )
    }

    /** Usage count, plus a bonus for apps used today or this week. */
    private fun score(app: AppItem, now: Long): Double {
        if (app.launches == 0) return 0.0
        val day = 24L * 60 * 60 * 1000
        val recency = when {
            now - app.lastUsed < day -> 3.0
            now - app.lastUsed < 7 * day -> 1.0
            else -> 0.0
        }
        return app.launches + recency
    }

    fun launch(context: Context, packageName: String, displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = if (displayId == Display.DEFAULT_DISPLAY) {
            null
        } else {
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        }
        return try {
            context.startActivity(intent, options)
            AppUsage.record(context, packageName)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
