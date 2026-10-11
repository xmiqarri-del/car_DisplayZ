package com.xmiqarri.cardisplayz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.xmiqarri.cardisplayz.apps.AppGridActivity
import com.xmiqarri.cardisplayz.overlay.OverlayService
import com.xmiqarri.cardisplayz.update.ApkDownloader
import com.xmiqarri.cardisplayz.update.ApkInstaller
import com.xmiqarri.cardisplayz.update.UpdateWorker
import java.io.File

/**
 * Settings screen. Updates are checked in the background; this screen only shows the
 * floating-button permission and installs a downloaded update when the user asks.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var grantButton: Button
    private lateinit var installButton: Button

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        UpdateWorker.schedule(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        OverlayService.start(this)   // no-op until the permission is granted
        refresh()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBackground(0xFF0A1220.toInt(), 0f)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            textSize = 14f
            setTextColor(0xFF8FA6C2.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(20))
        })

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFE6EEF8.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(status)

        grantButton = styledButton(getString(R.string.main_grant)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        root.addView(grantButton)

        root.addView(styledButton(getString(R.string.main_open_apps)) {
            startActivity(Intent(this, AppGridActivity::class.java))
        })

        installButton = styledButton(getString(R.string.main_install_update)) {
            installDownloadedUpdate(expectedVersionCode = -1)
        }
        installButton.visibility = View.GONE
        root.addView(installButton)

        return root
    }

    private fun styledButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(0xFF03131A.toInt())
            background = roundedBackground(0xFF2BB3C0.toInt(), dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) }
            setOnClickListener { onClick() }
        }

    private fun refresh() {
        val granted = Settings.canDrawOverlays(this)
        status.text = getString(if (granted) R.string.main_status_on else R.string.main_status_off)
        grantButton.visibility = if (granted) View.GONE else View.VISIBLE

        val cached = ApkDownloader.cachedApk(cacheDir)
        installButton.visibility = if (cached.exists()) View.VISIBLE else View.GONE
    }

    /** Opened from the "update ready" notification. Installs the verified APK. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(UpdateWorker.EXTRA_INSTALL_UPDATE, false)) return
        installDownloadedUpdate(intent.getIntExtra(UpdateWorker.EXTRA_VERSION_CODE, -1))
    }

    private fun installDownloadedUpdate(expectedVersionCode: Int) {
        val apk: File = ApkDownloader.cachedApk(cacheDir)
        if (!apk.exists() || !verifyCached(apk, expectedVersionCode)) {
            status.text = getString(R.string.install_missing)
            return
        }
        if (!ApkInstaller.canInstallPackages(this)) {
            ApkInstaller.openInstallPermissionSettings(this)
            return
        }
        if (!ApkInstaller.install(this, apk)) {
            status.text = getString(R.string.install_failed)
        }
    }

    private fun verifyCached(apk: File, expectedVersionCode: Int): Boolean {
        if (expectedVersionCode > 0) {
            return ApkInstaller.verifyPackage(this, apk, expectedVersionCode)
        }
        // Button press without a notification: accept any newer build of this app.
        val info = packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        return info.packageName == packageName && info.longVersionCode > BuildConfig.VERSION_CODE
    }
}
