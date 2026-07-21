package com.lhstack.suxi.dns.dns.resolver

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpDnsResolver(
    private val client: OkHttpClient,
    private val scheme: String,
    private val host: String,
    private val port: Int,
    private val path: String,
) : DnsResolver {
    override val description: String = "$scheme://$host:$port$path"

    override suspend fun resolve(query: ByteArray): ByteArray = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(description)
            .header("Accept", DNS_MEDIA_TYPE_STRING)
            .post(query.toRequestBody(DNS_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        check(it.isSuccessful) {
                            "$description 返回 HTTP ${it.code}"
                        }
                        val responseBody = checkNotNull(it.body) {
                            "$description 返回了空的 HTTP 响应体"
                        }.bytes()
                        validateDnsResponse(query, responseBody)
                        if (continuation.isActive) {
                            continuation.resume(responseBody)
                        }
                    }
                } catch (exception: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
            }
        })
    }

    companion object {
        private const val DNS_MEDIA_TYPE_STRING = "application/dns-message"
        private val DNS_MEDIA_TYPE = DNS_MEDIA_TYPE_STRING.toMediaType()

        fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}
