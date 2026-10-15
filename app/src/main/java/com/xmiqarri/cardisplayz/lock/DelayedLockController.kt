package com.xmiqarri.cardisplayz.lock

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xmiqarri.cardisplayz.AppSettings
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.dp
import java.util.Locale

/**
 * Delayed lock: pick 15, 30 or 60 seconds, the countdown is spoken aloud, then the doors lock.
 * Cancel stops it at any time before the doors lock.
 */
class DelayedLockController(private val activity: Activity) {

    /** Countdown ticks. Cleared by [reset]. */
    private val handler = Handler(Looper.getMainLooper())

    /** The lock sequence has its own handler, so cancelling the countdown never cuts it short. */
    private val lockHandler = Handler(Looper.getMainLooper())

    private var dialog: AlertDialog? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    /** Shows the 15 / 30 / 60 second choice. */
    fun showDurationPicker() {
        if (dialog != null) return
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = activity.dp(16)
            setPadding(pad, pad, pad, pad)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.delayed_lock_duration_hint)
                textSize = 15f
                setPadding(0, 0, 0, pad)
            })
            for (seconds in PRESETS) {
                addView(Button(activity).apply {
                    text = activity.getString(labelFor(seconds))
                    setOnClickListener { start(seconds) }
                })
            }
        }
        val d = AlertDialog.Builder(activity)
            .setTitle(R.string.delayed_lock_duration_title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    private fun start(seconds: Int) {
        reset()
        if (!CarLock.available(activity)) {
            Toast.makeText(activity, R.string.vehicle_unavailable_toast, Toast.LENGTH_LONG).show()
            return
        }

        val pad = activity.dp(16)
        val title = TextView(activity).apply {
            text = activity.getString(R.string.delayed_lock_dialog_title)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val counter = TextView(activity).apply {
            text = seconds.toString()
            textSize = 56f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(activity).apply {
            text = activity.getString(R.string.delayed_lock_dialog_subtitle)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(counter)
            addView(subtitle)
        }
        val d = AlertDialog.Builder(activity)
            .setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancel(true) }
            .setOnCancelListener { cancel(true) }
            .create()
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()

        speak(activity.getString(R.string.delayed_lock_speak_start, seconds))

        var remaining = seconds
        handler.postDelayed(object : Runnable {
            override fun run() {
                remaining--
                if (remaining <= 0) {
                    finish(counter, title, subtitle, d)
                    return
                }
                counter.text = remaining.toString()
                if (remaining <= 3) speak(remaining.toString())
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun finish(counter: TextView, title: TextView, subtitle: TextView, d: AlertDialog) {
        counter.text = "✓"
        title.setText(R.string.delayed_lock_done_title)
        subtitle.setText(R.string.delayed_lock_done_subtitle)
        CarLock.lockDoors(activity, lockHandler)
        speak(activity.getString(R.string.delayed_lock_speak_done))
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
        d.setCancelable(false)
        lockHandler.postDelayed({ if (d.isShowing) d.dismiss() }, 2500)
    }

    /** Stops the countdown. [announce] says so out loud and on screen when a countdown was running. */
    fun cancel(announce: Boolean) {
        val wasRunning = dialog != null
        reset()
        if (announce && wasRunning) {
            speak(activity.getString(R.string.delayed_lock_speak_cancelled))
            Toast.makeText(activity, R.string.delayed_lock_cancelled_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reset() {
        handler.removeCallbacksAndMessages(null)
        dialog?.let { if (it.isShowing) it.dismiss() }
        dialog = null
    }

    /** Call from the activity's onDestroy. */
    fun shutdown() {
        reset()
        tts?.shutdown()
        tts = null
    }

    private fun speak(text: String) {
        val engine = tts ?: TextToSpeech(activity.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                pendingSpeech?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE) }
                pendingSpeech = null
            }
        }.also { tts = it }
        if (ttsReady) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
        } else {
            pendingSpeech = text
        }
    }

    companion object {
        private val PRESETS = intArrayOf(15, 30, 60)
        private const val UTTERANCE = "delayed_lock"

        fun isEnabled(context: android.content.Context): Boolean = AppSettings.delayedLockEnabled(context)

        private fun labelFor(seconds: Int): Int = when (seconds) {
            15 -> R.string.delayed_lock_15s
            30 -> R.string.delayed_lock_30s
            else -> R.string.delayed_lock_60s
        }
    }
}
