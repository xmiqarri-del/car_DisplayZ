package com.xmiqarri.cardisplayz.adb

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Grants this app its own permissions by sending `pm grant` through ADB to this same device.
 * It connects to the device's own ADB on localhost. ADB must already be listening on port 5555,
 * which the user turns on once (adb tcpip 5555, or Wireless debugging). The app cannot turn it on itself.
 * The key pair is generated once and kept in the app's private storage.
 */
object AdbGranter {

    const val PACKAGE = "com.xmiqarri.cardisplayz"
    const val PORT = 5555

    /** Permissions this app needs that only ADB can grant. Each must be declared in AndroidManifest.xml. */
    val PERMISSIONS = listOf("android.permission.WRITE_SECURE_SETTINGS")

    class Result(
        val granted: List<String>,
        val failed: Map<String, String>,
        val error: String?,
    )

    /** Blocking call: run it off the main thread. */
    fun grantAll(context: Context): Result {
        val dadb = try {
            Dadb.create("127.0.0.1", PORT, loadOrCreateKey(context))
        } catch (e: Exception) {
            return Result(emptyList(), emptyMap(), e.message ?: e.javaClass.simpleName)
        }
        return try {
            val granted = mutableListOf<String>()
            val failed = mutableMapOf<String, String>()
            for (permission in PERMISSIONS) {
                val response = dadb.shell("pm grant $PACKAGE $permission")
                val output = response.allOutput.trim()
                if (response.exitCode == 0 && !output.contains("Exception") && !output.contains("Error")) {
                    granted += permission
                } else {
                    failed[permission] = output.ifEmpty { "exit ${response.exitCode}" }
                }
            }
            Result(granted, failed, null)
        } catch (e: Exception) {
            Result(emptyList(), emptyMap(), e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { dadb.close() }
        }
    }

    private fun loadOrCreateKey(context: Context): AdbKeyPair {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
