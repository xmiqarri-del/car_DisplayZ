package com.xmiqarri.cardisplayz.lock

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * Sends the lock commands of the head unit's own vehicle SDK (package com.autolink.manager).
 *
 * The SDK is reached through reflection, so this app builds and runs on any device. On a head unit
 * without the SDK every call returns null or false and nothing is sent. The command names and the
 * order of the lock sequence come from DisplayMirror 3.46.0, which runs them on this head unit.
 */
object CarLock {
    private const val TAG = "CarLock"
    private const val API_CLASS = "com.autolink.manager.Api"
    private const val CAR_MANAGER = "car"

    private const val SET_CENTER_LOCK = "setICC_CENTERLOCKSWT"
    private const val SET_HAZARD_REQ = "setICC_HAZARDLIGHTREQ"
    private const val SET_MIRROR_FOLD = "setICC_REARMIRRORFOLDCMD"
    private const val SET_AMBIENT_SWITCH = "setICC_SET_TDL_SWITCH"
    private const val AMBIENT_OFF = 2
    private const val POWER_MANAGER = "power"

    /** Screens to turn off after a lock. The head unit's own app uses 0 and 1; the car has 0, 2 to 5. */
    private val SCREEN_IDS = listOf(0, 1, 2, 3, 4, 5)

    /** Hazards that this app turns on count as its own, for the key-lock watch, for this long. */
    private const val OWN_HAZARD_WINDOW_MS = 4000L

    @Volatile
    private var manager: Any? = null

    @Volatile
    private var powerManager: Any? = null

    @Volatile
    private var ownHazardsUntil = 0L

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

    fun available(context: Context): Boolean = car(context) != null

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

    /** Hazards from this app's own lock are not a key-lock refusal. */
    fun ownHazardsActive(): Boolean = SystemClock.elapsedRealtime() < ownHazardsUntil

    /**
     * Locks the doors with the same sequence as the head unit: the hazards flash for one second,
     * the central lock is pulsed and the ambient light goes off at 1.1 s, the mirrors fold at
     * 1.25 s and the screens go off at 1.4 s.
     * Returns at once; the steps run on [handler]. Call only when [available] is true.
     */
    fun lockDoors(context: Context, handler: Handler) {
        ownHazardsUntil = SystemClock.elapsedRealtime() + OWN_HAZARD_WINDOW_MS
        pulse(context, handler, SET_HAZARD_REQ, 1)
        handler.postDelayed({
            pulse(context, handler, SET_HAZARD_REQ, 2)
            setInt(context, SET_CENTER_LOCK, 0)
        }, 1000)
        handler.postDelayed({
            setInt(context, SET_CENTER_LOCK, 2)
            turnOffAmbientLight(context, handler)
        }, 1100)
        handler.postDelayed({ pulse(context, handler, SET_MIRROR_FOLD, 1) }, 1250)
        handler.postDelayed({ turnOffScreens(context, handler) }, 1400)
    }

    /** The head unit sends the off command, then releases the switch 250 ms later. */
    private fun turnOffAmbientLight(context: Context, handler: Handler) {
        setInt(context, SET_AMBIENT_SWITCH, AMBIENT_OFF)
        handler.postDelayed({ setInt(context, SET_AMBIENT_SWITCH, 0) }, 250)
    }

    /** The power manager of the vehicle SDK, or null when it is not on this device. */
    private fun power(context: Context): Any? {
        powerManager?.let { return it }
        return try {
            val api = Class.forName(API_CLASS)
                .getMethod("createApi", Context::class.java)
                .invoke(null, context.applicationContext) ?: return null
            val power = api.javaClass.getMethod("getManager", String::class.java).invoke(api, POWER_MANAGER)
            powerManager = power
            power
        } catch (e: Throwable) {
            Log.w(TAG, "Power SDK not available: ${e.message}")
            null
        }
    }

    private fun displayState(power: Any, id: Int): Int? = try {
        power.javaClass.getMethod("getDisplayPowerState", Int::class.javaPrimitiveType!!)
            .invoke(power, id) as? Int
    } catch (e: Throwable) {
        null
    }

    /**
     * Turns the screens off the way DisplayMirror does: setDisplayPowerState(id, 0, 1), where state
     * 0 is off. DisplayMirror only does ids 0 and 1; this tries every id in [SCREEN_IDS]. The state
     * of each one is logged before and 700 ms after, so a wrong id shows up in the log.
     */
    private fun turnOffScreens(context: Context, handler: Handler) {
        val power = power(context) ?: return
        val before = SCREEN_IDS.associateWith { displayState(power, it) }
        for (id in SCREEN_IDS) {
            try {
                power.javaClass.getMethod(
                    "setDisplayPowerState",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                ).invoke(power, id, 0, 1)
            } catch (e: Throwable) {
                Log.w(TAG, "setDisplayPowerState($id, 0, 1) failed: ${e.message}")
            }
        }
        handler.postDelayed({
            val after = SCREEN_IDS.associateWith { displayState(power, it) }
            Log.i(TAG, "Screens off: state before $before, after $after")
        }, 700)
    }

    /** Same as the head unit's pulse: send 0 now and the value 100 ms later. */
    private fun pulse(context: Context, handler: Handler, setter: String, value: Int) {
        setInt(context, setter, 0)
        handler.postDelayed({ setInt(context, setter, value) }, 100)
    }
}
