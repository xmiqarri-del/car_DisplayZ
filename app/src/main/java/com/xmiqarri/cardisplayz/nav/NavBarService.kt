package com.xmiqarri.cardisplayz.nav

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import com.xmiqarri.cardisplayz.AppSettings
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.apps.MemoryCleaner
import com.xmiqarri.cardisplayz.dp

/**
 * Accessibility service used only for the system buttons: Back, Home, Recent apps and memory cleanup.
 * It shows a small bar at the bottom of the screen. It does not read screen content
 * (see res/xml/accessibility_service_config.xml). The user turns it on in Android's Accessibility settings.
 */
class NavBarService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var bar: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshBar()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: the service does not listen to screen events.
    }

    override fun onInterrupt() {
        // Not used.
    }

    override fun onDestroy() {
        instance = null
        removeBar()
        super.onDestroy()
    }

    /** Shows or hides the bottom bar according to the user's setting. */
    fun refreshBar() {
        if (AppSettings.navBarEnabled(this)) addBar() else removeBar()
    }

    private fun addBar() {
        if (bar != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(0xE6121E33.toInt())
            }
        }
        container.addView(button(R.drawable.ic_back, R.string.nav_back) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        })
        container.addView(button(R.drawable.ic_home, R.string.nav_home) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        })
        container.addView(button(R.drawable.ic_recents, R.string.nav_recents) {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        })
        container.addView(button(R.drawable.ic_clean, R.string.nav_clean) {
            val stopped = MemoryCleaner.clean(this)
            Toast.makeText(this, getString(R.string.clean_done, stopped), Toast.LENGTH_SHORT).show()
        })

        // NOT_FOCUSABLE: the bar never takes keyboard focus. NOT_TOUCH_MODAL: touches outside
        // the bar always reach the car's own screens underneath.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(6)
        }

        try {
            wm.addView(container, params)
            windowManager = wm
            bar = container
        } catch (e: RuntimeException) {
            // The system refused the overlay; the settings screen will show the service as off.
        }
    }

    private fun removeBar() {
        bar?.let { view -> runCatching { windowManager?.removeView(view) } }
        bar = null
    }

    private fun button(iconRes: Int, labelRes: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            setImageTintList(ColorStateList.valueOf(0xFFE6EEF8.toInt()))
            setBackgroundColor(0x00000000)
            contentDescription = this@NavBarService.getString(labelRes)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            setOnClickListener { onClick() }
        }

    companion object {
        /** The running service, or null when the user has not turned it on. */
        @Volatile
        var instance: NavBarService? = null
            private set
    }
}
