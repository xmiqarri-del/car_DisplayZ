package com.xmiqarri.cardisplayz.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Sends the lock commands of the head unit's own vehicle SDK (package com.autolink.manager).
 *
 * The SDK is reached through reflection, so this app builds and runs on any device. On a head unit
 * without the SDK every call returns null or false and nothing is sent. The command names and the
 * order of the lock sequence come from DisplayMirror 3.46.0, which runs them on this head unit.
 *
 * After a lock the screens, the daytime running lights and the ambient light are switched off. What
 * was switched off is saved, and it is all switched back on, with the command repeated until the
 * car reports it, as soon as the doors unlock.
 */
object CarLock {
    private const val TAG = "CarLock"
    private const val API_CLASS = "com.autolink.manager.Api"
    private const val CAR_MANAGER = "car"
    private const val POWER_MANAGER = "power"

    private const val SET_CENTER_LOCK = "setICC_CENTERLOCKSWT"
    private const val SET_HAZARD_REQ = "setICC_HAZARDLIGHTREQ"
    private const val SET_MIRROR_FOLD = "setICC_REARMIRRORFOLDCMD"

    /** Ambient light: 1 is on, 2 is off, and the switch is released with 0 after 250 ms. */
    private const val SET_AMBIENT_SWITCH = "setICC_SET_TDL_SWITCH"
    private const val GET_AMBIENT = "getTDL_SWITCHSTS"
    private const val AMBIENT_ON = 1
    private const val AMBIENT_OFF = 2

    /** Daytime running lights: the setter takes 1 for on and 2 for off; the state reads 3 when lit. */
    private const val SET_DRL = "setICC_DRLSW"
    private const val GET_DRL = "getFLZCU_DAYTIMELIGHT"
    private const val DRL_LIT = 3.0

    private const val GET_LOCK = "getLHFDOORLOCKSTS"
    private const val LOCK_UNLOCKED = 1.0
    private const val LOCK_LOCKED = 0.0

    /** The vehicle SDK's screen ids: 0 main, 1 cluster, 2 passenger, 3 climate, 4 roof. */
    private val SCREEN_IDS = listOf(0, 1, 2, 3, 4)
    private const val SCREEN_ON = 1
    private const val SCREEN_OFF = 0
    private const val SCREEN_ATTEMPTS = 3

    private val EXTERIOR_LIGHT_GETTERS = listOf(
        "getFLZCU_DAYTIMELIGHT",
        "getFLZCU_LOWBEAMSTS",
        "getFLZCU_HIGHBEAMSTS",
        "getFLZCU_FRONTFOGLIGHTSTS",
        "getFLZCU_REARFOGLIGHTSTS",
        "getFRZCU_1_PARKLIGHTSTS",
        "getFLZCU_LIGHTMAINSWITCHSTS",
    )

    /** Hazards that this app turns on count as its own, for the key-lock watch, for this long. */
    private const val OWN_HAZARD_WINDOW_MS = 4000L

    /** The power service connects in the background; its setters do nothing until it has. */
    private const val POWER_CONNECT_MS = 1500L

    /** After this long the lock is checked: locked means watch for the doors to open. */
    private const val LOCK_CHECK_MS = 5500L
    private const val WATCH_INTERVAL_MS = 1000L
    private const val UNLOCKED_READS_NEEDED = 2
    private const val RESTORE_ATTEMPTS = 8
    private const val RESTORE_CHECK_MS = 1500L

    private const val PREFS = "car_lock_state"
    private const val KEY_PENDING = "restore_pending"
    private const val KEY_DRL = "drl_off"
    private const val KEY_AMBIENT = "ambient_off"
    private const val KEY_SCREENS = "screens_off"

    @Volatile
    private var manager: Any? = null

    @Volatile
    private var powerManager: Any? = null

    @Volatile
    private var powerCreatedAt = 0L

    @Volatile
    private var ownHazardsUntil = 0L

    @Volatile
    private var sequenceRunning = false

    @Volatile
    private var watching = false

    @Volatile
    private var unlockedReads = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The car manager, or null when the vehicle SDK is not on this device. */
    private fun car(context: Context): Any? {
        manager?.let { return it }
        return try {
            val api = Class.forName(API_CLASS)
                .getMethod("createApi", Context::class.java)
                .invoke(null, context.applicationContext) ?: return null
            val car = api.javaClass.getMethod("getManager", String::class.java).invoke(api, CAR_MANAGER)
            manager = car
            car
        } catch (e: Throwable) {
            Log.w(TAG, "Vehicle SDK not available: ${e.message}")
            null
        }
    }

    /** The power manager of the vehicle SDK, or null when it is not on this device. */
    private fun power(context: Context): Any? {
        powerManager?.let { return it }
        return try {
            val api = Class.forName(API_CLASS)
                .getMethod("createApi", Context::class.java)
                .invoke(null, context.applicationContext) ?: return null
            val power = api.javaClass.getMethod("getManager", String::class.java).invoke(api, POWER_MANAGER)
            powerCreatedAt = SystemClock.elapsedRealtime()
            powerManager = power
            power
        } catch (e: Throwable) {
            Log.w(TAG, "Power SDK not available: ${e.message}")
            null
        }
    }

    private fun powerReady(): Boolean =
        powerCreatedAt != 0L && SystemClock.elapsedRealtime() - powerCreatedAt >= POWER_CONNECT_MS

    fun available(context: Context): Boolean = car(context) != null

    /**
     * Connects to the vehicle and power services ahead of time. The power service connects in the
     * background, and its setDisplayPowerState does nothing until that has finished, so a manager
     * created at the moment of the lock loses the first commands. Call this well before a lock.
     */
    fun warmUp(context: Context) {
        car(context)
        power(context)
    }

    /** Reads a getter of the car manager. Booleans become 1 and 0. Null when it cannot be read. */
    fun readNumber(context: Context, getter: String): Double? {
        val car = car(context) ?: return null
        return try {
            when (val value = car.javaClass.getMethod(getter).invoke(car)) {
                is Number -> value.toDouble()
                is Boolean -> if (value) 1.0 else 0.0
                else -> null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$getter failed: ${e.message}")
            null
        }
    }

    private fun setInt(context: Context, setter: String, value: Int): Boolean {
        val car = car(context) ?: return false
        return try {
            car.javaClass.getMethod(setter, Int::class.javaPrimitiveType!!).invoke(car, value)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "$setter($value) failed: ${e.message}")
            false
        }
    }

    /** Same as the head unit's pulse: send 0 now and the value 100 ms later. */
    private fun pulse(context: Context, handler: Handler, setter: String, value: Int) {
        setInt(context, setter, 0)
        handler.postDelayed({ setInt(context, setter, value) }, 100)
    }

    /** The head unit sends the ambient light command, then releases the switch 250 ms later. */
    private fun pulseAmbient(context: Context, handler: Handler, value: Int) {
        setInt(context, SET_AMBIENT_SWITCH, value)
        handler.postDelayed({ setInt(context, SET_AMBIENT_SWITCH, 0) }, 250)
    }

    private fun displayState(power: Any, id: Int): Int? = try {
        power.javaClass.getMethod("getDisplayPowerState", Int::class.javaPrimitiveType!!)
            .invoke(power, id) as? Int
    } catch (e: Throwable) {
        null
    }

    /** setDisplayPowerState(id, state, 1): state 0 is off and 1 is on, as DisplayMirror sends it. */
    private fun setDisplayState(power: Any, id: Int, state: Int) {
        try {
            power.javaClass.getMethod(
                "setDisplayPowerState",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ).invoke(power, id, state, 1)
        } catch (e: Throwable) {
            Log.w(TAG, "setDisplayPowerState($id, $state, 1) failed: ${e.message}")
        }
    }

    /** Hazards from this app's own lock are not a key-lock refusal. */
    fun ownHazardsActive(): Boolean = SystemClock.elapsedRealtime() < ownHazardsUntil

    // What this app switched off, kept so it survives a restart and can be switched back on.

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun savedScreens(p: SharedPreferences): List<Int> =
        (p.getString(KEY_SCREENS, "") ?: "").split(',').mapNotNull { it.toIntOrNull() }

    /** Records that a restore is owed, and what for. Later calls add to what is already saved. */
    @Synchronized
    private fun markPending(
        context: Context,
        drl: Boolean = false,
        ambient: Boolean = false,
        screens: List<Int> = emptyList(),
    ) {
        val p = prefs(context)
        val merged = (savedScreens(p) + screens).toSortedSet()
        p.edit()
            .putBoolean(KEY_PENDING, true)
            .putBoolean(KEY_DRL, p.getBoolean(KEY_DRL, false) || drl)
            .putBoolean(KEY_AMBIENT, p.getBoolean(KEY_AMBIENT, false) || ambient)
            .putString(KEY_SCREENS, merged.joinToString(","))
            .apply()
    }

    @Synchronized
    private fun clearPending(context: Context) {
        prefs(context).edit()
            .remove(KEY_PENDING)
            .remove(KEY_DRL)
            .remove(KEY_AMBIENT)
            .remove(KEY_SCREENS)
            .apply()
    }

    /**
     * Locks the doors with the same sequence as the head unit: the hazards flash for one second,
     * the central lock is pulsed and the ambient light goes off at 1.1 s, the mirrors fold at
     * 1.25 s, the screens go off at 1.4 s and the daytime running lights at 1.6 s. At 5.5 s the lock
     * is checked, and from then on the app waits for the doors to open and switches everything
     * back on. Returns at once; the steps run on [handler]. Call only when [available] is true.
     */
    fun lockDoors(context: Context, handler: Handler) {
        val app = context.applicationContext
        ownHazardsUntil = SystemClock.elapsedRealtime() + OWN_HAZARD_WINDOW_MS
        sequenceRunning = true
        markPending(app)
        pulse(app, handler, SET_HAZARD_REQ, 1)
        handler.postDelayed({
            pulse(app, handler, SET_HAZARD_REQ, 2)
            setInt(app, SET_CENTER_LOCK, 0)
        }, 1000)
        handler.postDelayed({
            setInt(app, SET_CENTER_LOCK, 2)
            turnOffAmbientLight(app, handler)
        }, 1100)
        handler.postDelayed({ pulse(app, handler, SET_MIRROR_FOLD, 1) }, 1250)
        handler.postDelayed({ turnOffScreens(app, handler) }, 1400)
        handler.postDelayed({ turnOffDaytimeLights(app, handler) }, 1600)
        handler.postDelayed({ afterLock(app, handler) }, LOCK_CHECK_MS)
    }

    private fun turnOffAmbientLight(context: Context, handler: Handler) {
        if (readNumber(context, GET_AMBIENT) == AMBIENT_ON.toDouble()) markPending(context, ambient = true)
        pulseAmbient(context, handler, AMBIENT_OFF)
    }

    private fun turnOffDaytimeLights(context: Context, handler: Handler) {
        if (readNumber(context, GET_DRL) != DRL_LIT) return
        markPending(context, drl = true)
        pulse(context, handler, SET_DRL, 2)
        Log.i(TAG, "Daytime running lights off")
    }

    /**
     * Turns the screens off the way DisplayMirror does: setDisplayPowerState(id, 0, 1), where state
     * 0 is off and 1 is on. Only screens that report on are sent the command, and they are saved to
     * be switched back on. The state is checked again 800 ms later and the command repeats for any
     * that are still on, up to [SCREEN_ATTEMPTS] times. Nothing is sent before the power service
     * has had time to connect.
     */
    private fun turnOffScreens(context: Context, handler: Handler, attempt: Int = 1) {
        val power = power(context) ?: return
        if (!powerReady()) {
            handler.postDelayed({ turnOffScreens(context, handler, attempt) }, 500)
            return
        }
        val states = SCREEN_IDS.associateWith { displayState(power, it) }
        val on = SCREEN_IDS.filter { states[it] == SCREEN_ON }
        Log.i(TAG, "Screens off, attempt $attempt: states $states, sending to $on")
        if (on.isEmpty()) return
        markPending(context, screens = on)
        for (id in on) setDisplayState(power, id, SCREEN_OFF)
        handler.postDelayed({
            val after = SCREEN_IDS.associateWith { displayState(power, it) }
            val stillOn = SCREEN_IDS.filter { after[it] == SCREEN_ON }
            Log.i(TAG, "Screens off, attempt $attempt: states after $after")
            if (stillOn.isNotEmpty() && attempt < SCREEN_ATTEMPTS) {
                turnOffScreens(context, handler, attempt + 1)
            }
        }, 800)
    }

    /** Logs the exterior lights after a lock, so a light that stays on can be matched to a setter. */
    private fun logExteriorLights(context: Context) {
        val states = EXTERIOR_LIGHT_GETTERS.associateWith { readNumber(context, it)?.toInt() }
        Log.i(TAG, "Exterior lights after lock: $states")
    }

    private fun afterLock(context: Context, handler: Handler) {
        sequenceRunning = false
        logExteriorLights(context)
        val lock = readNumber(context, GET_LOCK)
        if (lock == LOCK_LOCKED) {
            Log.i(TAG, "Doors locked; waiting for them to open")
            startWatching(context, handler)
        } else {
            Log.w(TAG, "Doors report $lock after the lock; switching everything back on")
            restore(context, handler, 1)
        }
    }

    /** Polls the lock state once a second. When the doors have opened it starts the restore. */
    private fun startWatching(context: Context, handler: Handler) {
        if (watching) return
        watching = true
        unlockedReads = 0
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!prefs(context).getBoolean(KEY_PENDING, false)) {
                    watching = false
                    return
                }
                if (!sequenceRunning) {
                    val lock = readNumber(context, GET_LOCK)
                    unlockedReads = if (lock == LOCK_UNLOCKED) unlockedReads + 1 else 0
                    if (unlockedReads >= UNLOCKED_READS_NEEDED) {
                        watching = false
                        Log.i(TAG, "Doors opened; switching everything back on")
                        restore(context, handler, 1)
                        return
                    }
                }
                handler.postDelayed(this, WATCH_INTERVAL_MS)
            }
        }, WATCH_INTERVAL_MS)
    }

    /**
     * Resumes a restore that a restart cut short, for example the head unit shutting down while the
     * car was locked. Call it when the app starts.
     */
    fun resumeRestore(context: Context) {
        val app = context.applicationContext
        if (sequenceRunning || !prefs(app).getBoolean(KEY_PENDING, false)) return
        warmUp(app)
        startWatching(app, mainHandler)
    }

    /**
     * Switches back on what the lock switched off: the screens, the daytime running lights and the
     * ambient light. After each attempt the car is asked whether it took effect, and the commands are
     * sent again, up to [RESTORE_ATTEMPTS] times, until it reports all of them.
     */
    private fun restore(context: Context, handler: Handler, attempt: Int) {
        val p = prefs(context)
        val screens = savedScreens(p)
        val drl = p.getBoolean(KEY_DRL, false)
        val ambient = p.getBoolean(KEY_AMBIENT, false)
        if (screens.isEmpty() && !drl && !ambient) {
            clearPending(context)
            return
        }
        Log.i(TAG, "Restore attempt $attempt: screens $screens, daytime lights $drl, ambient light $ambient")

        val power = if (screens.isEmpty()) null else power(context)
        if (power != null && powerReady()) {
            for (id in screens) {
                if (displayState(power, id) != SCREEN_ON) setDisplayState(power, id, SCREEN_ON)
            }
        }
        if (drl && readNumber(context, GET_DRL) != DRL_LIT) pulse(context, handler, SET_DRL, 1)
        if (ambient && readNumber(context, GET_AMBIENT) != AMBIENT_ON.toDouble()) {
            pulseAmbient(context, handler, AMBIENT_ON)
        }

        handler.postDelayed({
            val screensOk = screens.isEmpty() || (power != null && screens.all { displayState(power, it) == SCREEN_ON })
            val drlOk = !drl || readNumber(context, GET_DRL) == DRL_LIT
            val ambientOk = !ambient || readNumber(context, GET_AMBIENT) == AMBIENT_ON.toDouble()
            Log.i(TAG, "Restore attempt $attempt: screens $screensOk, daytime lights $drlOk, ambient light $ambientOk")
            if (screensOk && drlOk && ambientOk) {
                clearPending(context)
                Log.i(TAG, "Everything is back on")
            } else if (attempt < RESTORE_ATTEMPTS) {
                restore(context, handler, attempt + 1)
            } else {
                clearPending(context)
                Log.w(TAG, "Gave up after $RESTORE_ATTEMPTS attempts; the commands were sent each time")
            }
        }, RESTORE_CHECK_MS)
    }
}
