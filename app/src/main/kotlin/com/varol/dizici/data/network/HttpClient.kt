package com.varol.dizici.data.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private val AD_DOMAINS = setOf(
    "doubleclick.net", "googlesyndication.com", "adnxs.com",
    "amazon-adsystem.com", "googleadservices.com", "pagead2.googlesyndication.com",
    "googletagmanager.com", "google-analytics.com", "analytics.google.com",
    "ads.pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com",
    "criteo.com", "taboola.com", "outbrain.com", "revcontent.com",
    "bidswitch.net", "advertising.com", "contextweb.com",
    "popads.net", "popcash.net", "propellerads.com", "hilltopads.net",
    "hotjar.com", "scorecardresearch.com", "quantserve.com"
)

private class AdBlockInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (AD_DOMAINS.any { host.contains(it) }) {
            return Response.Builder()
                .code(200)
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .message("Blocked")
                .body("".toResponseBody())
                .build()
        }
        return chain.proceed(chain.request())
    }
}

object HttpClient {

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("SSL").also {
        it.init(null, trustAllCerts, SecureRandom())
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .addInterceptor(AdBlockInterceptor())
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build()
}
