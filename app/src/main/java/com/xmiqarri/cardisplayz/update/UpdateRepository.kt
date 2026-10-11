package com.xmiqarri.cardisplayz.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Fetches update.json from the latest release and decides whether an update exists. */
class UpdateRepository(
    private val client: OkHttpClient = SecureHttp.client,
) {
    /**
     * @throws UpdateException for invalid or unexpected server responses.
     * @throws IOException for network failures (the caller reports these as "no connection").
     */
    suspend fun checkForUpdate(manifestUrl: String, installedVersionCode: Int): UpdateCheck =
        withContext(Dispatchers.IO) {
            val url = UpdatePolicy.requireTrustedHttps(manifestUrl)
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException("Update server returned HTTP ${response.code}")
                }
                val body = response.body ?: throw UpdateException("Empty update response")
                // Manifest is tiny; refuse anything large before reading it.
                val source = body.source()
                source.request(MAX_MANIFEST_BYTES + 1)   // buffers at most limit+1 bytes
                if (source.buffer.size > MAX_MANIFEST_BYTES) {
                    throw UpdateException("Update manifest is too large")
                }
                val text = source.readUtf8()
                UpdatePolicy.parseManifest(text, installedVersionCode)
            }
        }

    private companion object {
        const val MAX_MANIFEST_BYTES = 64L * 1024
    }
}
