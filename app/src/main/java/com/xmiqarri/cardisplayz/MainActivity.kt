package com.xmiqarri.cardisplayz

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.xmiqarri.cardisplayz.apps.AppGridActivity
import com.xmiqarri.cardisplayz.apps.MemoryCleaner
import com.xmiqarri.cardisplayz.permissions.PermissionsActivity
import com.xmiqarri.cardisplayz.nav.NavBarService
import com.xmiqarri.cardisplayz.lock.FobLockAssist
import com.xmiqarri.cardisplayz.overlay.OverlayService
import com.xmiqarri.cardisplayz.update.ApkDownloader
import com.xmiqarri.cardisplayz.update.ApkInstaller
import com.xmiqarri.cardisplayz.update.UpdateWorker
import java.io.File
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xmiqarri.cardisplayz.adb.AdbGranter

/**
 * Settings screen, laid out like Android's own settings: grouped sections, rows and switches.
 * It is rebuilt on every visit so each row shows the current value.
 */
class MainActivity : ComponentActivity() {

    private lateinit var content: LinearLayout

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Lets the user pick any picture from the phone to use as the app background. */
    private val pickBackground =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { AppBackground.save(this@MainActivity, uri) }
                Toast.makeText(
                    this@MainActivity,
                    if (saved) R.string.background_saved else R.string.background_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                applyBackground()
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore any grant that was lost since the last start, in the background and only when something is missing.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { AdbGranter.restoreIfMissing(applicationContext) } }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply {
            addView(content)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        UpdateWorker.schedule(this)
        applyBackground()
        handleIntent(intent)
    }

    /** Uses the chosen picture as the window background, or the plain colour when none is set. */
    private fun applyBackground() {
        window.setBackgroundDrawable(AppBackground.drawable(this) ?: ColorDrawable(Theme.BACKGROUND_SOLID))
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
            setTextColor(Theme.TEXT_PRIMARY)
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
        sliderRow(
            label = { v -> getString(R.string.row_alpha, v) },
            min = 20,
            max = 100,
            value = AppSettings.floatingAlpha(this),
        ) { v ->
            AppSettings.setFloatingAlpha(this, v)
            OverlayService.refresh(this)
        }
        sliderRow(
            label = { v -> getString(R.string.row_size, v) },
            min = 30,
            max = 72,
            value = AppSettings.floatingSizeDp(this),
        ) { v ->
            AppSettings.setFloatingSizeDp(this, v)
            OverlayService.refresh(this)
        }

        section(getString(R.string.section_navigation))
        addRow(
            title = getString(R.string.row_navbar),
            subtitle = getString(R.string.row_navbar_sub),
            trailing = toggle(AppSettings.navBarEnabled(this)) { on -> onNavBarToggled(on) },
            onClick = null,
        )
        addRow(
            title = getString(R.string.row_nav_autohide),
            subtitle = getString(R.string.row_nav_autohide_sub),
            trailing = toggle(AppSettings.navAutoHide(this)) { on ->
                AppSettings.setNavAutoHide(this, on)
                NavBarService.instance?.refreshBar()
            },
            onClick = null,
        )
        sliderRow(
            label = { v -> getString(R.string.row_nav_size, v) },
            min = 36,
            max = 72,
            value = AppSettings.navSizeDp(this),
        ) { v ->
            AppSettings.setNavSizeDp(this, v)
            NavBarService.instance?.refreshBar()
        }
        sliderRow(
            label = { v -> getString(R.string.row_nav_alpha, v) },
            min = 20,
            max = 100,
            value = AppSettings.navOpacity(this),
        ) { v ->
            AppSettings.setNavOpacity(this, v)
            NavBarService.instance?.refreshBar()
        }
        addRow(
            title = getString(R.string.row_nav_service),
            subtitle = getString(if (NavBarService.instance != null) R.string.nav_on else R.string.nav_off),
            trailing = null,
            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )

        section(getString(R.string.section_locks))
        addRow(
            title = getString(R.string.row_delayed_lock),
            subtitle = getString(R.string.row_delayed_lock_sub),
            trailing = toggle(AppSettings.delayedLockEnabled(this)) { on ->
                AppSettings.setDelayedLockEnabled(this, on)
                render()
            },
            onClick = null,
        )
        addRow(
            title = getString(R.string.row_fob_lock),
            subtitle = getString(R.string.row_fob_lock_sub),
            trailing = toggle(AppSettings.fobLockAssist(this)) { on ->
                AppSettings.setFobLockAssist(this, on)
                FobLockAssist.sync(this)
                render()
            },
            onClick = null,
        )

        section(getString(R.string.section_appearance))
        addRow(
            title = getString(R.string.row_background),
            subtitle = getString(R.string.row_background_sub),
            trailing = null,
            onClick = { pickBackground.launch("image/*") },
        )
        if (AppBackground.isSet(this)) {
            addRow(
                title = getString(R.string.row_background_remove),
                subtitle = null,
                trailing = null,
                onClick = {
                    AppBackground.remove(this)
                    applyBackground()
                    render()
                },
            )
        }

        section(getString(R.string.section_device))
        deviceSettingRows().forEach { (titleRes, action) ->
            addRow(
                title = getString(titleRes),
                subtitle = null,
                trailing = null,
                onClick = { openSystemSettings(action) },
            )
        }

        section(getString(R.string.section_apps))
        addRow(
            title = getString(R.string.row_open_apps),
            subtitle = getString(R.string.row_open_apps_sub),
            trailing = null,
            onClick = { startActivity(Intent(this, AppGridActivity::class.java)) },
        )
        addRow(
            title = getString(R.string.row_clean),
            subtitle = getString(R.string.row_clean_sub),
            trailing = null,
            onClick = {
                lifecycleScope.launch {
                    val stopped = withContext(Dispatchers.IO) { MemoryCleaner.clean(this@MainActivity) }
                    val message = if (stopped == null) {
                        getString(R.string.clean_no_adb, AdbGranter.PORT)
                    } else {
                        getString(R.string.clean_done, stopped)
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            },
        )

        addRow(
            title = getString(R.string.row_app_permissions),
            subtitle = getString(R.string.row_app_permissions_sub),
            trailing = null,
            onClick = { startActivity(Intent(this, PermissionsActivity::class.java)) },
        )

        addRow(
            title = getString(R.string.row_grant),
            subtitle = getString(R.string.row_grant_sub),
            trailing = null,
            onClick = { grantPermissions() },
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
        if (isNewerBuild(ApkDownloader.cachedApk(cacheDir))) {
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

    /** Sends the grant commands through ADB on this device, off the main thread, then shows each result. */
    private fun grantPermissions() {
        Toast.makeText(this, R.string.grant_started, Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AdbGranter.grantAll(this@MainActivity) }
            val message = if (result.error != null) {
                getString(R.string.grant_no_adb, AdbGranter.PORT, result.error)
            } else {
                result.steps.joinToString("\n") { step ->
                    val line = (if (step.ok) "✓ " else "✗ ") + getString(step.labelRes)
                    if (step.ok || step.detail.isEmpty()) line else line + "\n    " + step.detail.take(200)
                }
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.row_grant)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            if (AppSettings.floatingEnabled(this@MainActivity)) OverlayService.start(this@MainActivity)
            render()
        }
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Theme.ACCENT)
            letterSpacing = 0.06f
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
            background = roundedBackground(Theme.SURFACE, dp(16).toFloat())
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
            setTextColor(Theme.TEXT_PRIMARY)
        })
        if (subtitle != null) {
            texts.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Theme.TEXT_SECONDARY)
                setPadding(0, dp(4), 0, 0)
            })
        }
        row.addView(texts)
        if (trailing != null) row.addView(trailing)
        content.addView(row)
    }

    /**
     * A card with a title that shows the current value and a slider under it.
     * [onCommit] runs once, when the finger lifts, so the floating button is not redrawn on every step.
     */
    private fun sliderRow(label: (Int) -> String, min: Int, max: Int, value: Int, onCommit: (Int) -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = roundedBackground(Theme.SURFACE, dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        val title = TextView(this).apply {
            text = label(value)
            textSize = 17f
            setTextColor(Theme.TEXT_PRIMARY)
        }
        val slider = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    title.text = label(progress + min)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    onCommit(bar.progress + min)
                }
            })
        }
        card.addView(title)
        card.addView(slider)
        content.addView(card)
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

    private fun onNavBarToggled(on: Boolean) {
        AppSettings.setNavBarEnabled(this, on)
        NavBarService.instance?.refreshBar()
        if (on && NavBarService.instance == null) {
            // The bar needs the accessibility service, which only the user can turn on.
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        render()
    }

    private fun onAutoUpdateToggled(on: Boolean) {
        AppSettings.setAutoUpdate(this, on)
        if (on) UpdateWorker.schedule(this) else UpdateWorker.cancelSchedule(this)
        render()
    }

    /** Shortcuts to Android's own settings pages. Only these fixed actions are ever opened. */
    private fun deviceSettingRows(): List<Pair<Int, String>> = listOf(
        R.string.row_wifi to Settings.ACTION_WIFI_SETTINGS,
        R.string.row_bluetooth to Settings.ACTION_BLUETOOTH_SETTINGS,
        R.string.row_display to Settings.ACTION_DISPLAY_SETTINGS,
        R.string.row_sound to Settings.ACTION_SOUND_SETTINGS,
        R.string.row_datetime to Settings.ACTION_DATE_SETTINGS,
        R.string.row_apps_settings to Settings.ACTION_APPLICATION_SETTINGS,
        R.string.row_location to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        R.string.row_storage to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        R.string.row_device_info to Settings.ACTION_DEVICE_INFO_SETTINGS,
        R.string.row_all_settings to Settings.ACTION_SETTINGS,
    )

    /** Opens a system settings page, or the main settings screen if this device lacks it. */
    private fun openSystemSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
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
        return isNewerBuild(apk)
    }

    /** True when [apk] exists and is a build of this app that is newer than the one installed now. */
    private fun isNewerBuild(apk: File): Boolean {
        if (!apk.exists()) return false
        val info = packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        return info.packageName == packageName && info.longVersionCode > BuildConfig.VERSION_CODE
    }

}
