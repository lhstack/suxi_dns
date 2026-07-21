package com.lhstack.suxi.dns.dns.resolver

import android.content.Context
import com.lhstack.suxi.dns.dns.resolveTlsEndpoint
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Http3DnsResolver(
    context: Context,
    private val executor: ExecutorService,
    private val host: String,
    private val port: Int,
    private val path: String,
) : DnsResolver {
    private val endpoint = resolveTlsEndpoint(host)

    private val engine: CronetEngine = buildEngine(context)

    override val description: String = if (endpoint.connectHostIsIp && endpoint.serverName != endpoint.connectHost) {
        "https://${endpoint.serverName}:$port$path (HTTP/3 → ${endpoint.connectHost})"
    } else {
        "https://${endpoint.serverName}:$port$path (HTTP/3)"
    }

    override suspend fun resolve(query: ByteArray): ByteArray = suspendCancellableCoroutine { continuation ->
        val responseBytes = ByteArrayOutputStream()
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String,
            ) {
                request.cancel()
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("$description 被重定向到 $newLocationUrl"),
                    )
                }
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                try {
                    check(info.httpStatusCode in 200..299) {
                        "$description 返回 HTTP ${info.httpStatusCode}"
                    }
                    val protocol = info.negotiatedProtocol.lowercase()
                    check(protocol.contains("h3") || protocol.contains("quic")) {
                        "$description 协商协议为 ${info.negotiatedProtocol}，不是 HTTP/3"
                    }
                    request.read(ByteBuffer.allocateDirect(READ_BUFFER_SIZE))
                } catch (exception: Exception) {
                    request.cancel()
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer,
            ) {
                byteBuffer.flip()
                val chunk = ByteArray(byteBuffer.remaining())
                byteBuffer.get(chunk)
                responseBytes.write(chunk)
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                try {
                    val response = responseBytes.toByteArray()
                    validateDnsResponse(query, response)
                    if (continuation.isActive) {
                        continuation.resume(response)
                    }
                } catch (exception: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
            }

            override fun onFailed(
                request: UrlRequest,
                info: UrlResponseInfo?,
                error: CronetException,
            ) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
        }

        val request = engine.newUrlRequestBuilder(requestUrl(), callback, executor)
            .setHttpMethod("POST")
            .addHeader("Accept", DNS_MEDIA_TYPE)
            .addHeader("Content-Type", DNS_MEDIA_TYPE)
            .setUploadDataProvider(ByteArrayUploadProvider(query), executor)
            .build()
        continuation.invokeOnCancellation { request.cancel() }
        request.start()
    }

    fun close() {
        engine.shutdown()
    }

    private fun buildEngine(context: Context): CronetEngine {
        val builder = ExperimentalCronetEngine.Builder(context.applicationContext)
            .enableHttp2(false)
            .enableQuic(true)
            .addQuicHint(endpoint.serverName, port, port)

        // 用户填 IP 且能映射到证书域名时：强制把该域名解析到该 IP，避免 VPN 内 bootstrap 失败。
        if (endpoint.connectHostIsIp && endpoint.serverName != endpoint.connectHost) {
            val rules = "MAP ${endpoint.serverName} ${endpoint.connectHost}"
            builder.setExperimentalOptions(
                """{"HostResolverRules":{"host_resolver_rules":"$rules"}}""",
            )
        } else if (endpoint.connectHostIsIp) {
            builder.addQuicHint(endpoint.connectHost, port, port)
        }
        return builder.build()
    }

    private fun requestUrl(): String {
        // 使用证书域名构造 URL，连接地址通过 HostResolverRules / QUIC hint 指向 IP。
        return "https://${endpoint.serverName}:$port$path"
    }

    /**
     * 非分块上传：onReadSucceeded 的 finalChunk 必须始终为 false。
     * 之前传 position == size 会触发 Cronet "Exception received from UploadDataProvider"。
     */
    private class ByteArrayUploadProvider(
        private val bytes: ByteArray,
    ) : UploadDataProvider() {
        private var position = 0

        override fun getLength(): Long = bytes.size.toLong()

        override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
            val remaining = bytes.size - position
            if (remaining <= 0) {
                uploadDataSink.onReadError(IOException("上传数据已读完"))
                return
            }
            if (byteBuffer.remaining() == 0) {
                uploadDataSink.onReadError(IOException("上传缓冲区容量为 0"))
                return
            }
            val count = minOf(byteBuffer.remaining(), remaining)
            byteBuffer.put(bytes, position, count)
            position += count
            // 非分块上传必须传 false。
            uploadDataSink.onReadSucceeded(false)
        }

        override fun rewind(uploadDataSink: UploadDataSink) {
            position = 0
            uploadDataSink.onRewindSucceeded()
        }
    }

    private companion object {
        const val DNS_MEDIA_TYPE = "application/dns-message"
        const val READ_BUFFER_SIZE = 32 * 1024
    }
}
