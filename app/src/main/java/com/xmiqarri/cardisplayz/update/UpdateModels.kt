package com.xmiqarri.cardisplayz.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Details of a newer build, as published in update.json. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val sha256: String,      // lowercase hex, 64 characters
    val sizeBytes: Long,
)

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val info: UpdateInfo) : UpdateCheck
}

/** An expected, user-readable failure (bad response, hash mismatch, etc.). */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

object UpdatePolicy {
    const val MAX_APK_BYTES: Long = 200L * 1024 * 1024

    /** Hosts an update may come from. Anything else is rejected, including redirects. */
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** Parses a URL and enforces https + an allowed host. Throws UpdateException otherwise. */
    fun requireTrustedHttps(url: String): HttpUrl {
        val parsed = url.toHttpUrlOrNull() ?: throw UpdateException("Invalid update URL")
        if (!parsed.isHttps) throw UpdateException("Update URL must use HTTPS")
        if (parsed.host !in ALLOWED_HOSTS) throw UpdateException("Update host is not allowed")
        return parsed
    }

    fun isTrustedHost(host: String): Boolean = host in ALLOWED_HOSTS

    private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")

    /**
     * Parses update.json:
     * {"version_name":"1.1.0","version_code":2,"apk_url":"https://...","sha256":"<64 hex>","size_bytes":123}
     * Returns UpToDate when the manifest is not newer than [installedVersionCode].
     */
    fun parseManifest(json: String, installedVersionCode: Int): UpdateCheck {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw UpdateException("Update manifest is not valid JSON", e)
        }

        val versionCode = obj.optInt("version_code", -1)
        if (versionCode < 0) throw UpdateException("Update manifest has no version_code")
        if (versionCode <= installedVersionCode) return UpdateCheck.UpToDate

        val versionName = obj.optString("version_name", "").trim()
        if (versionName.isEmpty() || versionName.length > 32) {
            throw UpdateException("Update manifest has an invalid version_name")
        }

        val apkUrl = obj.optString("apk_url", "")
        requireTrustedHttps(apkUrl)

        val sha256 = obj.optString("sha256", "").lowercase()
        if (!SHA256_REGEX.matches(sha256)) throw UpdateException("Update manifest has an invalid sha256")

        val size = obj.optLong("size_bytes", -1L)
        if (size <= 0 || size > MAX_APK_BYTES) throw UpdateException("Update size is missing or too large")

        return UpdateCheck.Available(
            UpdateInfo(
                versionName = versionName,
                versionCode = versionCode,
                apkUrl = apkUrl,
                sha256 = sha256,
                sizeBytes = size,
            )
        )
    }
}
