package com.xmiqarri.cardisplayz.nav

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.xmiqarri.cardisplayz.AppSettings
import com.xmiqarri.cardisplayz.DragTapListener
import com.xmiqarri.cardisplayz.MainActivity
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.WRAP
import com.xmiqarri.cardisplayz.dp

/**
 * Accessibility service that shows the system buttons at the top of the screen, over every app:
 * Settings, Home and Back. Each button is a round icon with its name written under it.
 * The bar can be dragged to any place on the screen, can be resized and made more transparent,
 * and can fade away when not in use.
 * It does not read screen content (see res/xml/accessibility_service_config.xml).
 * The user turns it on in Android's Accessibility settings.
 */
class NavBarService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var bar: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val fadeOut = Runnable {
        bar?.animate()?.alpha(opacity() * IDLE_FRACTION)?.setDuration(FADE_MS)?.start()
    }

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

    /**
     * Shows or hides the top bar according to the user's settings. Rebuilding it applies any change
     * to size or opacity, and keeps the bar where the user last left it.
     */
    fun refreshBar() {
        val here = barParams?.let { it.x to it.y }
        removeBar()
        if (AppSettings.navBarEnabled(this)) addBar(here)
    }

    private fun opacity(): Float = AppSettings.navOpacity(this) / 100f

    private fun addBar(startAt: Pair<Int, Int>?) {
        if (bar != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val sizeDp = AppSettings.navSizeDp(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = startAt ?: AppSettings.navPosition(this@NavBarService)
            x = saved?.first ?: 0
            y = saved?.second ?: dp(8)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(32).toFloat()
                setColor(0x66000000)
            }
            // Touches on the padding around the buttons also drag the bar.
            setOnTouchListener(dragListener(wm, params, this) {})
        }
        container.addView(navItem(android.R.drawable.ic_menu_preferences, getString(R.string.settings_title), sizeDp, wm, params, container) {
            openSettings()
        })
        container.addView(navItem(R.drawable.ic_home, getString(R.string.nav_home), sizeDp, wm, params, container) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        })
        container.addView(navItem(R.drawable.ic_back, getString(R.string.nav_back), sizeDp, wm, params, container) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        })

        try {
            wm.addView(container, params)
            windowManager = wm
            bar = container
            barParams = params
            if (startAt == null && AppSettings.navPosition(this) == null) {
                // First run: put the bar in the middle of the top edge once its width is known.
                container.post { centreBar(wm, container, params) }
            }
            wake()
        } catch (e: RuntimeException) {
            // The system refused the overlay; the settings screen will show the service as off.
        }
    }

    private fun centreBar(wm: WindowManager, container: View, params: WindowManager.LayoutParams) {
        if (bar !== container) return
        params.x = (resources.displayMetrics.widthPixels - container.width) / 2
        runCatching { wm.updateViewLayout(container, params) }
    }

    private fun removeBar() {
        handler.removeCallbacks(fadeOut)
        bar?.let { view -> runCatching { windowManager?.removeView(view) } }
        bar = null
        barParams = null
    }

    /** Brings the bar back to the chosen opacity and, when auto-hide is on, starts the fade-out timer again. */
    private fun wake() {
        val view = bar ?: return
        handler.removeCallbacks(fadeOut)
        view.animate().cancel()
        view.alpha = opacity()
        if (AppSettings.navAutoHide(this)) handler.postDelayed(fadeOut, IDLE_MS)
    }

    private fun saveBarPosition() {
        val params = barParams ?: return
        AppSettings.setNavPosition(this, params.x, params.y)
    }

    /** Opens the Car_DisplayZ settings screen from any app. */
    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** Drag-or-tap handling shared by the bar and its buttons. */
    private fun dragListener(
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        moveTarget: View,
        onTap: () -> Unit,
    ): DragTapListener = DragTapListener(
        wm = wm,
        params = params,
        slop = ViewConfiguration.get(this).scaledTouchSlop,
        moveTarget = moveTarget,
        onDown = { wake() },
        onDrop = { saveBarPosition() },
        onTap = onTap,
    )

    /** A round button with its name written under it. Dragging anywhere on it moves the whole bar. */
    private fun navItem(
        iconRes: Int,
        label: String,
        sizeDp: Int,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        container: View,
        onClick: () -> Unit,
    ): LinearLayout {
        val listener = dragListener(wm, params, container, onClick)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
            setOnTouchListener(listener)
            addView(ImageButton(this@NavBarService).apply {
                setImageResource(iconRes)
                setImageTintList(ColorStateList.valueOf(0xFFE6EEF8.toInt()))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xE6121E33.toInt())
                }
                contentDescription = label
                layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                setOnTouchListener(listener)
            })
            addView(TextView(this@NavBarService).apply {
                text = label
                textSize = 12f * sizeDp / 56f
                setTextColor(0xFFE6EEF8.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
                setOnTouchListener(listener)
            })
        }
    }

    companion object {
        /** The running service, or null when the user has not turned it on. */
        @Volatile
        var instance: NavBarService? = null
            private set

        private const val IDLE_MS = 4000L
        private const val FADE_MS = 400L

        /** Share of the chosen opacity that remains while the bar is faded out. */
        private const val IDLE_FRACTION = 0.15f
    }
}
