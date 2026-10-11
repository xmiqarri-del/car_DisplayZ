package com.xmiqarri.cardisplayz.update

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared HTTP client for update traffic. HTTPS only, modern TLS, trusted hosts only. */
object SecureHttp {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))   // TLS 1.2+ only, no cleartext
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
            // Network interceptors see every hop, including redirects, so each one is checked.
            val url = chain.request().url
            if (!url.isHttps) throw IOException("Cleartext request blocked")
            if (!UpdatePolicy.isTrustedHost(url.host)) throw IOException("Redirect to untrusted host blocked")
            chain.proceed(chain.request())
        }
        .build()
}
