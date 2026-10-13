package com.xmiqarri.cardisplayz.nav

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.xmiqarri.cardisplayz.AppSettings
import com.xmiqarri.cardisplayz.MainActivity
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.WRAP
import com.xmiqarri.cardisplayz.dp

/**
 * Accessibility service that shows the system buttons at the top of the screen, over every app:
 * Settings, Home and Back. Each button is a round icon with its name written under it.
 * It does not read screen content (see res/xml/accessibility_service_config.xml).
 * The user turns it on in Android's Accessibility settings.
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

    /** Shows or hides the top bar according to the user's setting. */
    fun refreshBar() {
        if (AppSettings.navBarEnabled(this)) addBar() else removeBar()
    }

    private fun addBar() {
        if (bar != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(32).toFloat()
                setColor(0x66000000)
            }
        }
        container.addView(navItem(android.R.drawable.ic_menu_preferences, getString(R.string.settings_title)) {
            openSettings()
        })
        container.addView(navItem(R.drawable.ic_home, getString(R.string.nav_home)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        })
        container.addView(navItem(R.drawable.ic_back, getString(R.string.nav_back)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        })

        // NOT_FOCUSABLE: the bar never takes keyboard focus. NOT_TOUCH_MODAL: touches outside
        // the bar always reach the screens underneath.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
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

    /** Opens the Car_DisplayZ settings screen from any app. */
    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** A round button with its name written under it. */
    private fun navItem(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
            addView(ImageButton(this@NavBarService).apply {
                setImageResource(iconRes)
                setImageTintList(ColorStateList.valueOf(0xFFE6EEF8.toInt()))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xE6121E33.toInt())
                }
                contentDescription = label
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                setOnClickListener { onClick() }
            })
            addView(TextView(this@NavBarService).apply {
                text = label
                textSize = 12f
                setTextColor(0xFFE6EEF8.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        }

    companion object {
        /** The running service, or null when the user has not turned it on. */
        @Volatile
        var instance: NavBarService? = null
            private set
    }
}
