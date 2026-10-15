package com.xmiqarri.cardisplayz.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.xmiqarri.cardisplayz.R
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Gives this app the access it needs by sending shell commands through ADB to this same device.
 * It connects to the device's own ADB on localhost:5555. ADB must already be listening on that port,
 * which the user turns on once from a computer (adb tcpip 5555). The app cannot turn it on itself.
 * The key pair is generated once and kept in the app's private storage. The first connection shows
 * the system's "Allow debugging?" prompt on the screen.
 *
 * Every command changes only this app's own access. Enabling the accessibility service adds this
 * app's service to the enabled list and keeps every other service already in it.
 */
object AdbGranter {

    const val PORT = 5555
    private const val HOST = "127.0.0.1"

    /** Component names only. Anything else in the setting is left untouched and reported. */
    private val COMPONENT_LIST = Regex("[A-Za-z0-9_.$/:]+")

    /** Runtime permissions granted through pm grant. Each one is declared in AndroidManifest.xml. */
    val RUNTIME_PERMISSIONS = listOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.HIGH_SAMPLING_RATE_SENSORS",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        "android.permission.WAKE_LOCK",
        "android.permission.USE_EXACT_ALARM",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    )

    /**
     * True when something this app should hold is missing: a runtime permission, the overlay,
     * or the install-packages access. Cheap and safe to call on any thread.
     */
    fun isMissing(context: Context): Boolean {
        val pm = context.packageManager
        val pkg = context.packageName
        val runtimeMissing = RUNTIME_PERMISSIONS.any { pm.checkPermission(it, pkg) != PackageManager.PERMISSION_GRANTED }
        return runtimeMissing || !Settings.canDrawOverlays(context) || !pm.canRequestPackageInstalls()
    }

    /** Re-applies the grants only when something is missing. Returns null when nothing was needed. Blocking. */
    fun restoreIfMissing(context: Context): Result? = if (isMissing(context)) grantAll(context) else null

    class Step(val labelRes: Int, val ok: Boolean, val detail: String)

    /** [error] is set when ADB could not be reached at all; then [steps] is empty. */
    class Result(val steps: List<Step>, val error: String?)

    /** Blocking call: run it off the main thread. */
    fun grantAll(context: Context): Result {
        val pkg = context.packageName
        val dadb = try {
            Dadb.create(HOST, PORT, loadOrCreateKey(context))
        } catch (e: Exception) {
            return Result(emptyList(), describe(e))
        }
        try {
            // Fails here when nothing listens on the port or the prompt on the screen is refused.
            try {
                dadb.shell("echo ok")
            } catch (e: Exception) {
                return Result(emptyList(), describe(e))
            }
            val steps = listOf(
                run(dadb, R.string.grant_step_overlay, "appops set $pkg SYSTEM_ALERT_WINDOW allow"),
                run(dadb, R.string.grant_step_notifications, "pm grant $pkg android.permission.POST_NOTIFICATIONS"),
                run(dadb, R.string.grant_step_install, "appops set $pkg REQUEST_INSTALL_PACKAGES allow"),
                run(dadb, R.string.grant_step_appops, "appops set $pkg USE_FULL_SCREEN_INTENT allow 2>/dev/null; appops set $pkg PROJECT_MEDIA allow 2>/dev/null; echo done"),
                run(dadb, R.string.grant_step_runtime, RUNTIME_PERMISSIONS.joinToString("; ") { "pm grant $pkg $it 2>/dev/null" } + "; echo done"),
                enableAccessibility(dadb, pkg),
            )
            return Result(steps, null)
        } finally {
            runCatching { dadb.close() }
        }
    }

    private fun run(dadb: Dadb, labelRes: Int, command: String): Step = try {
        val response = dadb.shell(command)
        val output = response.allOutput.trim()
        val ok = response.exitCode == 0 && !output.contains("Exception") && !output.startsWith("Error")
        Step(labelRes, ok, output)
    } catch (e: Exception) {
        Step(labelRes, false, describe(e))
    }

    /** Adds the top-buttons service to the enabled accessibility services, keeping the others. */
    private fun enableAccessibility(dadb: Dadb, pkg: String): Step {
        val label = R.string.grant_step_accessibility
        return try {
            val fullName = "$pkg/$pkg.nav.NavBarService"
            val shortName = "$pkg/.nav.NavBarService"
            val current = dadb.shell("settings get secure enabled_accessibility_services").output.trim()
            val enabled = if (current.isEmpty() || current == "null") {
                emptyList()
            } else {
                current.split(':').filter { it.isNotBlank() }
            }
            if (enabled.none { it == fullName || it == shortName }) {
                val updated = (enabled + fullName).joinToString(":")
                if (!updated.matches(COMPONENT_LIST)) {
                    return Step(label, false, "Unexpected value, left unchanged: $current")
                }
                // Single quotes: a '$' in an inner class name must reach the setting unchanged.
                val put = dadb.shell("settings put secure enabled_accessibility_services '$updated'")
                if (put.exitCode != 0) return Step(label, false, put.allOutput.trim())
            }
            val on = dadb.shell("settings put secure accessibility_enabled 1")
            Step(label, on.exitCode == 0, on.allOutput.trim())
        } catch (e: Exception) {
            Step(label, false, describe(e))
        }
    }

    /** Package names only, so nothing else can reach the shell command. */
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")

    /**
     * Force-stops each package through ADB, which ends every process of the app, including ones
     * that are on screen or running a service. Returns how many were sent, or null when ADB is not reachable.
     * Blocking: run it off the main thread.
     */
    fun forceStop(context: Context, packages: List<String>): Int? {
        val safe = packages.filter { PACKAGE_NAME.matches(it) }
        if (safe.isEmpty()) return 0
        val dadb = try {
            Dadb.create(HOST, PORT, loadOrCreateKey(context))
        } catch (e: Exception) {
            return null
        }
        try {
            dadb.shell(safe.joinToString("; ") { "am force-stop $it 2>/dev/null" } + "; echo done")
            return safe.size
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { dadb.close() }
        }
    }

    /**
     * Deletes one installed app for the user through ADB ("pm uninstall"), with no system dialog.
     * Returns null when ADB is not reachable, "Success" when it worked, or Android's error text.
     * Blocking: run it off the main thread.
     */
    fun uninstall(context: Context, packageName: String): String? {
        if (!PACKAGE_NAME.matches(packageName)) return "Invalid package name"
        val dadb = try {
            Dadb.create(HOST, PORT, loadOrCreateKey(context))
        } catch (e: Exception) {
            return null
        }
        try {
            val response = dadb.shell("pm uninstall $packageName")
            val output = response.allOutput.trim()
            return if (output.contains("Success")) "Success" else output.ifEmpty { "Failure" }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { dadb.close() }
        }
    }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName

    private fun loadOrCreateKey(context: Context): AdbKeyPair {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
