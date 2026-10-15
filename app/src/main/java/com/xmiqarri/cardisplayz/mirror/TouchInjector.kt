package com.xmiqarri.cardisplayz.mirror

import dadb.Dadb
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Sends taps and swipes to the main display (display 0) through the app's own ADB connection,
 * with `input -d 0`. Commands run in order on one background thread, and the connection is reused.
 */
class TouchInjector(private val connect: () -> Dadb) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var dadb: Dadb? = null

    fun tap(x: Int, y: Int) = send("input -d 0 tap $x $y")

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        send("input -d 0 swipe $x1 $y1 $x2 $y2 $durationMs")

    /** Only integers reach the command text, so nothing else can be sent to the shell. */
    private fun send(command: String) {
        executor.execute {
            try {
                val connection = dadb ?: connect().also { dadb = it }
                connection.shell(command)
            } catch (e: Exception) {
                closeConnection()
            }
        }
    }

    private fun closeConnection() {
        runCatching { dadb?.close() }
        dadb = null
    }

    fun close() {
        executor.execute { closeConnection() }
        executor.shutdown()
    }
}
