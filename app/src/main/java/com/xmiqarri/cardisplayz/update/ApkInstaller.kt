package com.xmiqarri.cardisplayz.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {

    /** Returns true when the user has allowed this app to install other apps (Android 8+). */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows installs from this app. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Checks the APK is for this app and is the expected build. Android also enforces that the
     * signing key matches the installed app, so a different key is rejected by the system.
     */
    fun verifyPackage(context: Context, apk: File, expectedVersionCode: Int): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        return info.packageName == context.packageName && info.longVersionCode == expectedVersionCode.toLong()
    }

    /**
     * Opens the system package installer for [apk]. The file is shared through FileProvider
     * with read-only access for the installer only.
     * @return false if the app could not start the installer.
     */
    fun install(context: Context, apk: File): Boolean {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            apk
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        }
    }
}
