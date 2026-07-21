package com.lhstack.suxi.dns.dns.resolver

import android.net.VpnService
import com.lhstack.suxi.dns.model.DnsProtocol
import com.lhstack.suxi.dns.model.DnsServerConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class UpstreamResolution(
    val upstream: String,
    val response: ByteArray,
)

class CompositeResolver(
    private val vpnService: VpnService,
    configs: List<DnsServerConfig>,
) : Closeable {
    private val httpClient = HttpDnsResolver.createClient()
    private val cronetExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val http3Resolvers = mutableListOf<Http3DnsResolver>()
    private val resolvers = configs.filter(DnsServerConfig::enabled).map(::createResolver)

    init {
        require(resolvers.isNotEmpty()) { "至少需要启用一个 DNS 上游服务器" }
    }

    suspend fun resolve(query: ByteArray): UpstreamResolution = coroutineScope {
        val results = Channel<Result<UpstreamResolution>>(resolvers.size)
        val requests = resolvers.map { resolver ->
            async {
                results.send(
                    runCatching {
                        UpstreamResolution(
                            upstream = resolver.description,
                            response = resolver.resolve(query),
                        )
                    }.recoverCatching { error ->
                        throw UpstreamFailure(resolver.description, error)
                    },
                )
            }
        }
        val failures = mutableListOf<Throwable>()
        try {
            repeat(resolvers.size) {
                val result = results.receive()
                result.onSuccess { return@coroutineScope it }
                result.exceptionOrNull()?.let(failures::add)
            }
            throw UpstreamResolutionException(failures)
        } finally {
            requests.forEach { it.cancel() }
            results.close()
        }
    }

    override fun close() {
        http3Resolvers.forEach(Http3DnsResolver::close)
        cronetExecutor.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun createResolver(config: DnsServerConfig): DnsResolver {
        config.validate()
        return when (config.protocol) {
            DnsProtocol.UDP -> UdpDnsResolver(vpnService, config.host, config.port)
            DnsProtocol.HTTP -> HttpDnsResolver(
                httpClient,
                "http",
                config.host,
                config.port,
                config.path,
            )
            DnsProtocol.HTTPS -> HttpDnsResolver(
                httpClient,
                "https",
                config.host,
                config.port,
                config.path,
            )
            DnsProtocol.HTTP3 -> Http3DnsResolver(
                vpnService,
                cronetExecutor,
                config.host,
                config.port,
                config.path,
            ).also(http3Resolvers::add)
        }
    }
}

class UpstreamFailure(
    val upstream: String,
    cause: Throwable,
) : Exception("$upstream: ${cause.message ?: cause::class.simpleName.orEmpty()}", cause)

class UpstreamResolutionException(
    failures: List<Throwable>,
) : Exception(
    failures.joinToString(
        prefix = "所有 DNS 上游均失败: ",
        separator = "; ",
    ) { it.message ?: it::class.simpleName.orEmpty() },
) {
    init {
        failures.forEach(::addSuppressed)
    }
}
