package com.xmiqarri.cardisplayz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.xmiqarri.cardisplayz.apps.AppGridActivity
import com.xmiqarri.cardisplayz.overlay.OverlayService
import com.xmiqarri.cardisplayz.update.ApkDownloader
import com.xmiqarri.cardisplayz.update.ApkInstaller
import com.xmiqarri.cardisplayz.update.UpdateWorker
import java.io.File

/**
 * Settings screen, laid out like Android's own settings: grouped sections, rows and switches.
 * It is rebuilt on every visit so each row shows the current value.
 */
class MainActivity : ComponentActivity() {

    private lateinit var content: LinearLayout

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0A1220.toInt())
            addView(content)
        })

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
        if (AppSettings.floatingEnabled(this)) OverlayService.start(this)
        render()
    }

    /** Rebuilds the whole list from the saved state. */
    private fun render() {
        content.removeAllViews()
        val canDraw = Settings.canDrawOverlays(this)

        content.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(WHITE)
            setPadding(dp(4), dp(8), 0, dp(4))
        })

        section(getString(R.string.section_floating))
        addRow(
            title = getString(R.string.row_floating),
            subtitle = floatingStatusText(canDraw),
            trailing = toggle(AppSettings.floatingEnabled(this)) { on -> onFloatingToggled(on) },
            onClick = null,
        )
        addRow(
            title = getString(R.string.row_permission),
            subtitle = getString(if (canDraw) R.string.perm_granted else R.string.perm_missing),
            trailing = null,
            onClick = { openOverlayPermission() },
        )
        addRow(
            title = getString(R.string.row_boot),
            subtitle = getString(R.string.row_boot_sub),
            trailing = toggle(AppSettings.startOnBoot(this)) { on -> AppSettings.setStartOnBoot(this, on) },
            onClick = null,
        )

        section(getString(R.string.section_apps))
        addRow(
            title = getString(R.string.row_open_apps),
            subtitle = getString(R.string.row_open_apps_sub),
            trailing = null,
            onClick = { startActivity(Intent(this, AppGridActivity::class.java)) },
        )

        section(getString(R.string.section_updates))
        addRow(
            title = getString(R.string.row_auto_update),
            subtitle = getString(R.string.row_auto_update_sub),
            trailing = toggle(AppSettings.autoUpdate(this)) { on -> onAutoUpdateToggled(on) },
            onClick = null,
        )
        addRow(
            title = getString(R.string.row_check_now),
            subtitle = lastCheckText(),
            trailing = null,
            onClick = { UpdateWorker.checkNow(this) },
        )
        if (ApkDownloader.cachedApk(cacheDir).exists()) {
            addRow(
                title = getString(R.string.row_install),
                subtitle = getString(R.string.row_install_sub),
                trailing = null,
                onClick = { installDownloadedUpdate(expectedVersionCode = -1) },
            )
        }

        section(getString(R.string.section_about))
        addRow(
            title = getString(R.string.row_version),
            subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            trailing = null,
            onClick = null,
        )
        addRow(
            title = getString(R.string.row_package),
            subtitle = packageName,
            trailing = null,
            onClick = null,
        )
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF2BB3C0.toInt())
            setPadding(dp(6), dp(24), 0, dp(2))
        })
    }

    private fun toggle(checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun addRow(title: String, subtitle: String?, trailing: View?, onClick: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
            background = roundedBackground(0xFF121E33.toInt(), dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(WHITE)
        })
        if (subtitle != null) {
            texts.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(0xFF8FA6C2.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }
        row.addView(texts)
        if (trailing != null) row.addView(trailing)
        content.addView(row)
    }

    private fun floatingStatusText(canDraw: Boolean): String = getString(
        when {
            !AppSettings.floatingEnabled(this) -> R.string.floating_off
            !canDraw -> R.string.floating_needs_perm
            else -> R.string.floating_on
        }
    )

    private fun lastCheckText(): String {
        val at = AppSettings.lastCheckAt(this)
        if (at == 0L) return getString(R.string.check_never)
        val ago = DateUtils.getRelativeTimeSpanString(
            at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
        return when (AppSettings.lastCheckState(this)) {
            "available" -> getString(R.string.check_available, AppSettings.lastCheckVersion(this), ago)
            "error" -> getString(R.string.check_error, ago)
            else -> getString(R.string.check_none, ago)
        }
    }

    private fun onFloatingToggled(on: Boolean) {
        AppSettings.setFloatingEnabled(this, on)
        if (on) {
            if (Settings.canDrawOverlays(this)) OverlayService.start(this) else openOverlayPermission()
        } else {
            OverlayService.stop(this)
        }
        render()
    }

    private fun onAutoUpdateToggled(on: Boolean) {
        AppSettings.setAutoUpdate(this, on)
        if (on) UpdateWorker.schedule(this) else UpdateWorker.cancelSchedule(this)
        render()
    }

    private fun openOverlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    /** Opened from the "update ready" notification. Installs the verified APK. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(UpdateWorker.EXTRA_INSTALL_UPDATE, false)) return
        installDownloadedUpdate(intent.getIntExtra(UpdateWorker.EXTRA_VERSION_CODE, -1))
    }

    private fun installDownloadedUpdate(expectedVersionCode: Int) {
        val apk: File = ApkDownloader.cachedApk(cacheDir)
        if (!apk.exists() || !verifyCached(apk, expectedVersionCode)) {
            Toast.makeText(this, R.string.install_missing, Toast.LENGTH_LONG).show()
            return
        }
        if (!ApkInstaller.canInstallPackages(this)) {
            ApkInstaller.openInstallPermissionSettings(this)
            return
        }
        if (!ApkInstaller.install(this, apk)) {
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show()
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

    private val WHITE: Int = 0xFFFFFFFF.toInt()
}
