package com.xmiqarri.cardisplayz.permissions

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.xmiqarri.cardisplayz.MATCH
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.WRAP
import com.xmiqarri.cardisplayz.adb.AdbGranter
import com.xmiqarri.cardisplayz.dp
import com.xmiqarri.cardisplayz.roundedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists this app's permissions with a switch each, grouped like the system's app page.
 *
 * A switch can only turn a permission ON, through ADB on this device (AdbGranter.grantOne).
 * Turning one OFF opens the system app settings: the app never revokes its own permissions,
 * so the restore-if-missing check cannot undo a choice the user made.
 */
class PermissionsActivity : ComponentActivity() {

    /** One row. [permission] is a manifest permission; [appop] is an app operation. [minSdk] 0 means any. */
    private class Entry(
        val labelRes: Int,
        val minSdk: Int = 0,
        val permission: String? = null,
        val appop: String? = null,
    )

    private val runtime = listOf(
        Entry(R.string.perm_storage_read, permission = "android.permission.READ_EXTERNAL_STORAGE"),
        Entry(R.string.perm_storage_write, permission = "android.permission.WRITE_EXTERNAL_STORAGE"),
        Entry(R.string.perm_location_fine, permission = "android.permission.ACCESS_FINE_LOCATION"),
        Entry(R.string.perm_location_coarse, permission = "android.permission.ACCESS_COARSE_LOCATION"),
        Entry(R.string.perm_sensors, permission = "android.permission.HIGH_SAMPLING_RATE_SENSORS"),
        Entry(R.string.perm_camera, permission = "android.permission.CAMERA"),
        Entry(R.string.perm_microphone, permission = "android.permission.RECORD_AUDIO"),
        Entry(R.string.perm_audio_settings, permission = "android.permission.MODIFY_AUDIO_SETTINGS"),
        Entry(R.string.perm_bt_connect, minSdk = 31, permission = "android.permission.BLUETOOTH_CONNECT"),
        Entry(R.string.perm_bt_advertise, minSdk = 31, permission = "android.permission.BLUETOOTH_ADVERTISE"),
        Entry(R.string.perm_bt_scan, minSdk = 31, permission = "android.permission.BLUETOOTH_SCAN"),
        Entry(R.string.perm_wifi_state, permission = "android.permission.ACCESS_WIFI_STATE"),
        Entry(R.string.perm_network_state, permission = "android.permission.ACCESS_NETWORK_STATE"),
        Entry(R.string.perm_multicast, permission = "android.permission.CHANGE_WIFI_MULTICAST_STATE"),
        Entry(R.string.perm_wake_lock, permission = "android.permission.WAKE_LOCK"),
        Entry(R.string.perm_exact_alarm, permission = "android.permission.USE_EXACT_ALARM"),
        Entry(R.string.perm_battery, permission = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
        Entry(R.string.perm_media_projection, permission = "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"),
        Entry(R.string.perm_media_playback, permission = "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"),
        Entry(R.string.perm_notifications, minSdk = 33, permission = "android.permission.POST_NOTIFICATIONS"),
    )

    private val other = listOf(
        Entry(R.string.perm_overlay, appop = "SYSTEM_ALERT_WINDOW"),
        Entry(R.string.perm_install, appop = "REQUEST_INSTALL_PACKAGES"),
    )

    private lateinit var content: LinearLayout

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
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Rebuilds every row from the current state of the system. */
    private fun render() {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = getString(R.string.title_app_permissions)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(WHITE)
            setPadding(dp(4), dp(8), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.perms_hint)
            textSize = 13f
            setTextColor(0xFF8FA6C2.toInt())
            setPadding(dp(4), 0, dp(4), 0)
        })

        section(getString(R.string.perms_section_permissions))
        runtime.filter { Build.VERSION.SDK_INT >= it.minSdk }.forEach { addRow(it) }

        section(getString(R.string.perms_section_other))
        other.forEach { addRow(it) }

        addAction(getString(R.string.perms_open_system)) { openSystemSettings() }
    }

    private fun isGranted(e: Entry): Boolean = when {
        e.permission != null -> checkSelfPermission(e.permission) == PackageManager.PERMISSION_GRANTED
        e.appop == "SYSTEM_ALERT_WINDOW" -> Settings.canDrawOverlays(this)
        e.appop == "REQUEST_INSTALL_PACKAGES" -> packageManager.canRequestPackageInstalls()
        else -> false
    }

    /** The exact grant command for this row. Built only from the constants above and this package name. */
    private fun grantCommand(e: Entry): String =
        if (e.permission != null) {
            "pm grant $packageName ${e.permission}"
        } else {
            "appops set $packageName ${e.appop} allow"
        }

    private fun onSwitched(e: Entry, on: Boolean) {
        if (!on) {
            // Turning off is not done here. Open the system page and show the real state on return.
            Toast.makeText(this, R.string.perms_hint, Toast.LENGTH_LONG).show()
            openSystemSettings()
            return
        }
        if (isGranted(e)) return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { AdbGranter.grantOne(applicationContext, grantCommand(e)) }
            if (!ok) {
                Toast.makeText(
                    this@PermissionsActivity,
                    getString(R.string.perms_no_adb, AdbGranter.PORT),
                    Toast.LENGTH_LONG,
                ).show()
            }
            render()
        }
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

    private fun addRow(e: Entry) {
        val granted = isGranted(e)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
            background = roundedBackground(0xFF121E33.toInt(), dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        texts.addView(TextView(this).apply {
            text = getString(e.labelRes)
            textSize = 17f
            setTextColor(WHITE)
        })
        texts.addView(TextView(this).apply {
            text = getString(if (granted) R.string.perms_on else R.string.perms_off)
            textSize = 13f
            setTextColor(0xFF8FA6C2.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(texts)
        row.addView(Switch(this).apply {
            isChecked = granted
            setOnCheckedChangeListener { _, on -> onSwitched(e, on) }
        })
        content.addView(row)
    }

    private fun addAction(title: String, onClick: () -> Unit) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFF2BB3C0.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(0xFF121E33.toInt(), dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        })
    }

    private fun openSystemSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private val WHITE: Int = 0xFFFFFFFF.toInt()
}
