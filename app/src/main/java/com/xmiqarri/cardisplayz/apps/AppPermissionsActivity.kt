package com.xmiqarri.cardisplayz.apps

import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Typeface
import android.os.Bundle
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
 * Permissions of one app, opened from its long-press menu.
 *
 * Runtime permissions and the special-access ones in AdbGranter.APP_OPS get a switch, changed through
 * ADB (pm grant/revoke or appops set). Other permissions are listed for information only: Android
 * grants them at install time and no command can switch them.
 */
class AppPermissionsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "package"
    }

    private enum class Kind { RUNTIME, APP_OP, OTHER }

    private class Entry(val permission: String, val label: String, val kind: Kind, val granted: Boolean)

    private lateinit var pkg: String
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_PACKAGE)
        if (name == null) {
            finish()
            return
        }
        pkg = name
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

    private fun render() {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = appLabel()
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(WHITE)
            setPadding(dp(4), dp(8), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.app_perms_hint)
            textSize = 13f
            setTextColor(0xFF8FA6C2.toInt())
            setPadding(dp(4), 0, dp(4), 0)
        })

        val entries = loadEntries()
        if (entries.isEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.perms_none)
                textSize = 16f
                setTextColor(0xFF8FA6C2.toInt())
                setPadding(dp(6), dp(24), 0, 0)
            })
            return
        }

        section(getString(R.string.perms_section_permissions))
        entries.filter { it.kind == Kind.RUNTIME }.forEach { addSwitchRow(it) }

        section(getString(R.string.perms_section_other))
        entries.filter { it.kind == Kind.APP_OP }.forEach { addSwitchRow(it) }
        entries.filter { it.kind == Kind.OTHER }.forEach { addInfoRow(it) }
    }

    private fun appLabel(): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /** Reads what the app requests and what is currently allowed, using PackageManager and AppOps only. */
    private fun loadEntries(): List<Entry> {
        val pm = packageManager
        val info = try {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        val requested = info.requestedPermissions ?: return emptyList()
        val uid = info.applicationInfo?.uid ?: return emptyList()
        val appOps = getSystemService(AppOpsManager::class.java)

        return requested.distinct().map { permission ->
            val definition = try {
                pm.getPermissionInfo(permission, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            val label = definition?.loadLabel(pm)?.toString() ?: permission.substringAfterLast('.')
            val opName = AdbGranter.APP_OPS[permission]
            val granted = pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
            when {
                opName != null -> {
                    val op = AppOpsManager.permissionToOp(permission)
                    val allowed = op != null &&
                        appOps.unsafeCheckOpNoThrow(op, uid, pkg) == AppOpsManager.MODE_ALLOWED
                    Entry(permission, label, Kind.APP_OP, allowed)
                }
                definition != null &&
                    (definition.protectionLevel and 0xF) == PermissionInfo.PROTECTION_DANGEROUS ->
                    Entry(permission, label, Kind.RUNTIME, granted)
                else -> Entry(permission, label, Kind.OTHER, granted)
            }
        }
    }

    private fun onSwitched(e: Entry, on: Boolean) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                AdbGranter.setPermission(applicationContext, pkg, e.permission, on)
            }
            if (!ok) {
                Toast.makeText(this@AppPermissionsActivity, getString(R.string.perms_no_adb, AdbGranter.PORT), Toast.LENGTH_LONG).show()
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

    private fun addSwitchRow(e: Entry) {
        val row = row()
        row.addView(texts(e.label, getString(if (e.granted) R.string.perms_on else R.string.perms_off)))
        row.addView(Switch(this).apply {
            isChecked = e.granted
            setOnCheckedChangeListener { _, on -> onSwitched(e, on) }
        })
        content.addView(row)
    }

    private fun addInfoRow(e: Entry) {
        val row = row()
        row.addView(texts(e.label, getString(if (e.granted) R.string.perms_granted_auto else R.string.perms_off)))
        content.addView(row)
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(16), dp(16), dp(16))
        background = roundedBackground(0xFF121E33.toInt(), dp(16).toFloat())
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    private fun texts(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        addView(TextView(this@AppPermissionsActivity).apply {
            text = title
            textSize = 17f
            setTextColor(WHITE)
        })
        addView(TextView(this@AppPermissionsActivity).apply {
            text = subtitle
            textSize = 13f
            setTextColor(0xFF8FA6C2.toInt())
            setPadding(0, dp(4), 0, 0)
        })
    }

    private val WHITE: Int = 0xFFFFFFFF.toInt()
}
