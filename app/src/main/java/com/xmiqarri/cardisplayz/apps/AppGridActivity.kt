package com.xmiqarri.cardisplayz.apps

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.graphics.drawable.GradientDrawable
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
import com.xmiqarri.cardisplayz.MATCH
import com.xmiqarri.cardisplayz.adb.AdbGranter
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.WRAP
import com.xmiqarri.cardisplayz.dp
import com.xmiqarri.cardisplayz.roundedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opened from the floating button. Shows the apps the driver uses most at the top, a search box,
 * and category chips. Tapping an app launches it and closes this screen. Long-pressing an app
 * the user installed offers to delete it (Android shows its own confirmation).
 */
class AppGridActivity : ComponentActivity() {

    private var allApps: List<AppItem> = emptyList()
    private var currentFilter = AppFilter.ALL
    private val chipViews = mutableMapOf<AppFilter, TextView>()

    private val gridAdapter = AppAdapter(
        compact = false,
        onClick = { launchApp(it) },
        onLongClick = { confirmUninstall(it) },
    )
    private val recentAdapter = AppAdapter(
        compact = true,
        onClick = { launchApp(it) },
        onLongClick = { confirmUninstall(it) },
    )

    private lateinit var search: EditText
    private lateinit var recentLabel: TextView
    private lateinit var recentRecycler: RecyclerView
    private lateinit var grid: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        reload()
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
        if (AppRepository.launch(this, app.packageName)) finish()
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
            if (selected) 0xFF2BB3C0.toInt() else 0xFF152238.toInt(),
            dp(20).toFloat()
        )
        chip.setTextColor(if (selected) 0xFF03131A.toInt() else 0xFFB8C7DA.toInt())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0E1A2B.toInt(), 0xFF05080F.toInt())
            )
            setPadding(dp(24), dp(18), dp(24), dp(14))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.apps_title)
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.hint_long_press)
            textSize = 13f
            setTextColor(0xFF8FA6C2.toInt())
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) }
        })

        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setHintTextColor(0x66FFFFFF)
            setTextColor(0xFFFFFFFF.toInt())
            setSingleLine(true)
            background = roundedBackground(0xFF152238.toInt(), dp(16).toFloat())
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

        recentLabel = TextView(this).apply {
            text = getString(R.string.section_recent)
            textSize = 14f
            setTextColor(0xFF8FA6C2.toInt())
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
