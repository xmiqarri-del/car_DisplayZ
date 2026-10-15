package com.xmiqarri.cardisplayz.apps

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xmiqarri.cardisplayz.AppBackground
import com.xmiqarri.cardisplayz.AppSettings
import com.xmiqarri.cardisplayz.MATCH
import com.xmiqarri.cardisplayz.adb.AdbGranter
import com.xmiqarri.cardisplayz.mirror.MirrorConsentActivity
import com.xmiqarri.cardisplayz.mirror.MirrorService
import com.xmiqarri.cardisplayz.mirror.RearDisplay
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.Theme
import com.xmiqarri.cardisplayz.WRAP
import com.xmiqarri.cardisplayz.dp
import com.xmiqarri.cardisplayz.lock.DelayedLockController
import com.xmiqarri.cardisplayz.roundedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opened from the floating button. Shows the apps the driver uses most at the top, a search box,
 * and category chips. Tapping an app launches it and closes this screen. Long-pressing an app opens
 * its menu: permissions, app info, force stop, and delete for apps the user installed (through ADB, without the system dialog).
 */
class AppGridActivity : ComponentActivity() {

    private var allApps: List<AppItem> = emptyList()
    private var currentFilter = AppFilter.ALL
    private val chipViews = mutableMapOf<AppFilter, TextView>()

    private val gridAdapter = AppAdapter(
        compact = false,
        onClick = { launchApp(it) },
        onLongClick = { showAppMenu(it) },
    )
    private val recentAdapter = AppAdapter(
        compact = true,
        onClick = { launchApp(it) },
        onLongClick = { showAppMenu(it) },
    )

    private lateinit var search: EditText
    private lateinit var recentLabel: TextView
    private lateinit var recentRecycler: RecyclerView
    private lateinit var grid: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onDestroy() {
        delayedLock.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        reload()
        refreshRearBar()
    }

    private fun reload() {
        lifecycleScope.launch {
            allApps = withContext(Dispatchers.IO) { AppRepository.load(this@AppGridActivity) }
            applyFilters()
        }
    }

    private fun applyFilters() {
        val query = search.text.toString().trim().lowercase()

        val visible = allApps.filter { app ->
            currentFilter.matches(app) && (query.isEmpty() || app.label.lowercase().contains(query))
        }
        gridAdapter.submit(visible)

        val recent = allApps.filter { it.launches > 0 }.take(8)
        val showRecent = query.isEmpty() && recent.isNotEmpty()
        val recentVisibility = if (showRecent) View.VISIBLE else View.GONE
        recentLabel.visibility = recentVisibility
        recentRecycler.visibility = recentVisibility
        recentAdapter.submit(recent)

        chipViews.forEach { (filter, chip) -> styleChip(chip, filter == currentFilter) }
    }

    private fun launchApp(app: AppItem) {
        if (AppSettings.launchOnRear(this)) {
            // Stays on this screen so the driver can keep choosing apps for the rear screen.
            val rear = RearDisplay.selected(this)
            if (rear == null) {
                Toast.makeText(this, R.string.rear_missing, Toast.LENGTH_SHORT).show()
            } else if (!AppRepository.launch(this, app.packageName, rear.displayId)) {
                Toast.makeText(this, R.string.rear_missing, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (AppRepository.launch(this, app.packageName)) finish()
    }

    private lateinit var launchButton: TextView
    private lateinit var rearButton: TextView
    private lateinit var mirrorButton: TextView
    private lateinit var delayedLockButton: TextView
    private val delayedLock by lazy { DelayedLockController(this) }

    /** Row of three buttons: where apps open, which screen is the rear one, and showing the main screen there. */
    private fun buildRearBar(): View {
        launchButton = pill()
        rearButton = pill()
        mirrorButton = pill()
        delayedLockButton = pill()
        delayedLockButton.setOnClickListener { delayedLock.showDurationPicker() }
        launchButton.setOnClickListener { toggleLaunchTarget() }
        rearButton.setOnClickListener { chooseRearDisplay() }
        mirrorButton.setOnClickListener { toggleMirror() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            addView(launchButton)
            addView(rearButton)
            addView(mirrorButton)
            addView(delayedLockButton)
        }
    }

    private fun pill(): TextView = TextView(this).apply {
        textSize = 14f
        setPadding(dp(14), dp(9), dp(14), dp(9))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
    }

    private fun refreshRearBar() {
        val rear = RearDisplay.selected(this)
        launchButton.text = getString(if (AppSettings.launchOnRear(this)) R.string.rear_launch_on else R.string.rear_launch_off)
        rearButton.text = if (rear == null) getString(R.string.rear_none) else getString(R.string.rear_label, rear.name)
        mirrorButton.text = getString(if (MirrorService.running) R.string.mirror_stop else R.string.mirror_start)
        delayedLockButton.text = getString(R.string.delayed_lock_button)
        delayedLockButton.visibility = if (DelayedLockController.isEnabled(this)) View.VISIBLE else View.GONE
        listOf(launchButton, rearButton, mirrorButton, delayedLockButton).forEach {
            it.background = roundedBackground(Theme.INPUT, dp(20).toFloat())
            it.setTextColor(Theme.TEXT_ON_SURFACE_CHIP)
        }
    }

    private fun toggleLaunchTarget() {
        AppSettings.setLaunchOnRear(this, !AppSettings.launchOnRear(this))
        refreshRearBar()
    }

    private fun chooseRearDisplay() {
        val displays = RearDisplay.candidates(this)
        if (displays.isEmpty()) {
            Toast.makeText(this, R.string.rear_missing, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rear_choose_title)
            .setItems(displays.map { it.name }.toTypedArray()) { _, which ->
                AppSettings.setRearDisplayId(this, displays[which].displayId)
                refreshRearBar()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Starts or stops showing the main screen on the rear screen. Starting asks for screen-capture consent. */
    private fun toggleMirror() {
        if (MirrorService.running) {
            MirrorService.stop(this)
            refreshRearBar()
            return
        }
        val rear = RearDisplay.selected(this)
        if (rear == null) {
            Toast.makeText(this, R.string.rear_missing, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, MirrorConsentActivity::class.java)
                .putExtra(MirrorService.EXTRA_DISPLAY_ID, rear.displayId)
        )
    }

    /** Long-press menu: permissions, app info, force stop, and delete for apps the user installed. */
    private fun showAppMenu(app: AppItem) {
        val actions = mutableListOf<Pair<Int, () -> Unit>>(
            R.string.menu_permissions to { openAppPermissions(app) },
            R.string.menu_app_info to { openAppInfo(app) },
            R.string.menu_force_stop to { forceStopApp(app) },
        )
        if (!app.isSystem) actions += R.string.menu_delete to { confirmUninstall(app) }
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAppPermissions(app: AppItem) {
        startActivity(
            Intent(this, AppPermissionsActivity::class.java)
                .putExtra(AppPermissionsActivity.EXTRA_PACKAGE, app.packageName)
        )
    }

    private fun openAppInfo(app: AppItem) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
        )
    }

    /** Stops every process of the app through ADB. */
    private fun forceStopApp(app: AppItem) {
        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) { AdbGranter.forceStop(this@AppGridActivity, listOf(app.packageName)) }
            val message = if (sent == null) getString(R.string.clean_no_adb, AdbGranter.PORT)
            else getString(R.string.force_stop_done, app.label)
            Toast.makeText(this@AppGridActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Asks before deleting. Only apps the user installed can be deleted. */
    private fun confirmUninstall(app: AppItem) {
        if (app.isSystem) {
            Toast.makeText(this, R.string.system_app_locked, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.uninstall_title, app.label))
            .setPositiveButton(R.string.uninstall_confirm) { _, _ -> deleteApp(app) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Deletes the app through ADB, without the system dialog, then refreshes the list. */
    private fun deleteApp(app: AppItem) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AdbGranter.uninstall(this@AppGridActivity, app.packageName) }
            val message = when {
                result == null -> getString(R.string.clean_no_adb, AdbGranter.PORT)
                result == "Success" -> getString(R.string.uninstall_done, app.label)
                else -> getString(R.string.uninstall_error, app.label, result)
            }
            Toast.makeText(this@AppGridActivity, message, Toast.LENGTH_LONG).show()
            if (result == "Success") reload()
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = roundedBackground(
            if (selected) Theme.ACCENT else Theme.INPUT,
            dp(20).toFloat()
        )
        chip.setTextColor(if (selected) Theme.ON_ACCENT else Theme.TEXT_ON_SURFACE_CHIP)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppBackground.drawable(this@AppGridActivity) ?: GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Theme.BACKGROUND_TOP, Theme.BACKGROUND_BOTTOM)
            )
            setPadding(dp(24), dp(18), dp(24), dp(14))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.apps_title)
            textSize = 24f
            setTextColor(Theme.TEXT_PRIMARY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.hint_long_press)
            textSize = 13f
            setTextColor(Theme.TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) }
        })

        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setHintTextColor(Theme.HINT)
            setTextColor(Theme.TEXT_PRIMARY)
            setSingleLine(true)
            background = roundedBackground(Theme.INPUT, dp(16).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            doAfterTextChanged { applyFilters() }
        }
        root.addView(search)

        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        AppFilter.values().forEach { filter ->
            val chip = TextView(this).apply {
                text = getString(filter.labelRes)
                textSize = 15f
                setPadding(dp(18), dp(9), dp(18), dp(9))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
                setOnClickListener {
                    currentFilter = filter
                    applyFilters()
                }
            }
            chipViews[filter] = chip
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll)
        root.addView(buildRearBar())

        recentLabel = TextView(this).apply {
            text = getString(R.string.section_recent)
            textSize = 14f
            setTextColor(Theme.TEXT_SECONDARY)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dp(16)
                bottomMargin = dp(2)
            }
        }
        root.addView(recentLabel)

        recentRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AppGridActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        root.addView(recentRecycler)

        val columns = (resources.displayMetrics.widthPixels / dp(170)).coerceIn(3, 8)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AppGridActivity, columns)
            adapter = gridAdapter
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) }
        }
        root.addView(grid)

        return root
    }
}

/** Category chips. Matching uses the category the app declares to Android. */
enum class AppFilter(val labelRes: Int) {
    ALL(R.string.filter_all),
    FREQUENT(R.string.filter_frequent),
    MEDIA(R.string.filter_media),
    MAPS(R.string.filter_maps),
    COMMUNICATION(R.string.filter_comm),
    OTHER(R.string.filter_other);

    fun matches(app: AppItem): Boolean = when (this) {
        ALL -> true
        FREQUENT -> app.launches > 0
        MEDIA -> app.category == ApplicationInfo.CATEGORY_AUDIO ||
            app.category == ApplicationInfo.CATEGORY_VIDEO ||
            app.category == ApplicationInfo.CATEGORY_IMAGE
        MAPS -> app.category == ApplicationInfo.CATEGORY_MAPS
        COMMUNICATION -> app.category == ApplicationInfo.CATEGORY_SOCIAL
        OTHER -> app.category !in setOf(
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_IMAGE,
            ApplicationInfo.CATEGORY_MAPS,
            ApplicationInfo.CATEGORY_SOCIAL,
        )
    }
}
