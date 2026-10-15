package com.xmiqarri.cardisplayz.lock

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.xmiqarri.cardisplayz.AppSettings

/**
 * "Lock from the key while the car is on".
 *
 * When the car refuses a lock from the key, it flashes the hazard lights ten times. If the car is
 * on, in Park, not moving, every door, the trunk and the hood are closed, and the doors are still
 * unlocked, this locks the doors. It is off by default and only runs while the setting is on.
 *
 * Limitation: DisplayMirror counts flashes of the two turn lamps through a vehicle listener. This
 * version counts the hazard switch state (getHAZARDLIGHTSW) instead, because the lamp signals are
 * not exposed here. Test it on the car before relying on it.
 */
object FobLockAssist {
    private const val TAG = "FobLockAssist"
    private const val POLL_MS = 100L
    private const val RUN_GAP_MS = 1600L
    private const val REQUIRED_FLASHES = 10
    private const val COOLDOWN_MS = 30_000L
    private const val VERIFY_DELAY_MS = 5_000L

    private const val POWER_MODE = "getFLZCU_9_POWERMODE"
    private const val POWER_ON = 2.0
    private const val GEAR = "getVCU_1_G_PRNDGEARACT"
    private const val GEAR_P = 1.0
    private const val SPEED = "getVEHICLESPEEDVSOSIG"
    private const val HAZARD_SWITCH = "getHAZARDLIGHTSW"
    private const val LOCK_STATE = "getLHFDOORLOCKSTS"
    private const val LOCK_UNLOCKED = 1.0
    private const val LOCK_LOCKED = 0.0

    private val CLOSED_GETTERS = listOf(
        "getLHFDOORSTS",
        "getRHFDOORSTS",
        "getLHRDOORSTS",
        "getRHRDOORSTS",
        "getTRUNKSTS",
        "getFRZCU_HOODSTS",
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    // The fields below are used only on the worker thread, except appContext, which is set in sync().
    @Volatile
    private var appContext: Context? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var hazardsLit = false
    private var inRun = false
    private var flashes = 0
    private var lastLockAt = 0L

    private val endOfRun = Runnable { onRunEnded() }

    private val poll = object : Runnable {
        override fun run() {
            sample()
            handler?.postDelayed(this, POLL_MS)
        }
    }

    /** Starts or stops the watch to match the setting. Call it whenever the setting changes. */
    @Synchronized
    fun sync(context: Context) {
        appContext = context.applicationContext
        if (AppSettings.fobLockAssist(context)) start() else stop()
    }

    private fun start() {
        if (worker != null) return
        val thread = HandlerThread("fob-lock-assist").also { it.start() }
        val h = Handler(thread.looper)
        worker = thread
        handler = h
        h.post(poll)
        Log.i(TAG, "Watching for a refused key lock")
    }

    private fun stop() {
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        handler = null
        hazardsLit = false
        inRun = false
        flashes = 0
        Log.i(TAG, "Stopped watching")
    }

    private fun sample() {
        val ctx = appContext ?: return
        val on = CarLock.readNumber(ctx, HAZARD_SWITCH)?.let { it != 0.0 } ?: return
        if (on && !hazardsLit) {
            // A rising edge is one flash. Every flash extends the run; a quiet gap ends it.
            if (!inRun) {
                inRun = true
                flashes = 0
            }
            flashes++
            handler?.removeCallbacks(endOfRun)
            handler?.postDelayed(endOfRun, RUN_GAP_MS)
        }
        hazardsLit = on
    }

    private fun onRunEnded() {
        inRun = false
        val count = flashes
        flashes = 0
        val ctx = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        val reason = rejectReason(ctx, count, now)
        if (reason != null) {
            Log.i(TAG, "$count flashes ignored: $reason")
            return
        }
        lastLockAt = now
        Log.i(TAG, "$count flashes: the car refused a key lock, locking the doors")
        CarLock.lockDoors(ctx, mainHandler)
        handler?.postDelayed({ verify(ctx) }, VERIFY_DELAY_MS)
    }

    /** Null when the lock should happen; otherwise the reason it must not. */
    private fun rejectReason(ctx: Context, count: Int, now: Long): String? {
        if (count != REQUIRED_FLASHES) return "$count flashes, needs exactly $REQUIRED_FLASHES"
        if (CarLock.ownHazardsActive()) return "our own hazard request is on"
        if (lastLockAt != 0L && now - lastLockAt < COOLDOWN_MS) return "locked moments ago"
        if (!CarLock.available(ctx)) return "vehicle SDK unavailable"

        val power = CarLock.readNumber(ctx, POWER_MODE) ?: return "power mode unreadable"
        if (power != POWER_ON) return "power mode $power, not ON"

        val gear = CarLock.readNumber(ctx, GEAR) ?: return "gear unreadable"
        if (gear != GEAR_P) return "gear $gear, not P"

        val speed = CarLock.readNumber(ctx, SPEED) ?: return "speed unreadable"
        if (speed < 0.0 || speed >= 1.0) return "speed $speed"

        for (getter in CLOSED_GETTERS) {
            val value = CarLock.readNumber(ctx, getter) ?: return "$getter unreadable"
            if (value != 0.0) return "$getter = $value (open or unreadable)"
        }

        val lock = CarLock.readNumber(ctx, LOCK_STATE) ?: return "lock state unreadable"
        return when (lock) {
            LOCK_UNLOCKED -> null
            LOCK_LOCKED -> "doors already locked"
            else -> "lock state $lock"
        }
    }

    private fun verify(ctx: Context) {
        val lock = CarLock.readNumber(ctx, LOCK_STATE)
        Log.i(TAG, if (lock == LOCK_LOCKED) "Doors locked" else "Doors still report $lock")
    }
}
