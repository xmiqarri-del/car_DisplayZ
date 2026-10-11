package com.baghdady.cardisplayz.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads the update APK into the app's private cache folder, verifies its SHA-256
 * against the value from update.json, and only then makes it available for install.
 * On any failure the partial or mismatched file is deleted.
 */
class ApkDownloader(
    private val client: OkHttpClient = SecureHttp.client,
    cacheDir: File,
) {
    private val updateDir = File(cacheDir, UPDATE_DIR_NAME)

    /**
     * @param onProgress called on the IO thread with 0..100 when the size is known.
     * @return the verified APK file, inside the app's private cache.
     */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            UpdatePolicy.requireTrustedHttps(info.apkUrl)
            updateDir.mkdirs()
            clearOldUpdates()

            val partFile = File(updateDir, PART_NAME)
            val finalFile = File(updateDir, FINAL_NAME)
            try {
                streamToFile(info, partFile, onProgress)

                if (partFile.length() != info.sizeBytes) {
                    throw UpdateException("Downloaded size does not match the manifest")
                }

                val actualHash = partFile.sha256Hex()
                if (!constantTimeHexEquals(actualHash, info.sha256)) {
                    throw UpdateException("SHA-256 mismatch: the update was discarded")
                }

                if (finalFile.exists() && !finalFile.delete()) {
                    throw UpdateException("Could not replace the previous update file")
                }
                if (!partFile.renameTo(finalFile)) {
                    throw UpdateException("Could not prepare the update file")
                }
                finalFile
            } catch (e: UpdateException) {
                cleanup(partFile, finalFile)
                throw e
            } catch (e: IOException) {
                cleanup(partFile, finalFile)
                throw UpdateException("Download interrupted: ${e.message ?: "network error"}", e)
            } catch (e: Exception) {
                cleanup(partFile, finalFile)
                throw e
            }
        }

    private suspend fun streamToFile(info: UpdateInfo, target: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(info.apkUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateException("Download server returned HTTP ${response.code}")
            }
            val body = response.body ?: throw UpdateException("Empty download response")

            val declared = body.contentLength()
            if (declared != -1L && declared != info.sizeBytes) {
                throw UpdateException("Server size does not match the manifest")
            }

            var total = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()   // honours cancellation
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > UpdatePolicy.MAX_APK_BYTES) {
                            throw UpdateException("Update is larger than the allowed limit")
                        }
                        output.write(buffer, 0, read)
                        onProgress(((total * 100) / info.sizeBytes).toInt().coerceAtMost(100))
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        }
    }

    private fun clearOldUpdates() {
        updateDir.listFiles()?.forEach { it.delete() }
    }

    private fun cleanup(vararg files: File) {
        files.forEach { if (it.exists()) it.delete() }
    }

    companion object {
        const val UPDATE_DIR_NAME = "updates"
        private const val PART_NAME = "update.apk.part"
        private const val FINAL_NAME = "update.apk"

        /** Location of the last verified update APK (only valid after [download] succeeded). */
        fun cachedApk(cacheDir: File): File = File(File(cacheDir, UPDATE_DIR_NAME), FINAL_NAME)
    }
}
