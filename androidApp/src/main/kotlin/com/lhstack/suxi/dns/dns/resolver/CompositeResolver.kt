package com.lhstack.suxi.dns.dns.resolver

import android.content.Context
import android.net.VpnService
import com.lhstack.suxi.dns.model.DnsProtocol
import com.lhstack.suxi.dns.model.DnsServerConfig
import com.lhstack.suxi.dns.model.DnsServerGroup
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

/**
 * 按分组解析：
 * - 组间按列表顺序 fallback，仅当前一组全部上游失败才尝试下一组。
 * - 组内启用的上游并发竞速，取第一个通过校验的成功响应并取消其余请求。
 * - 所有启用组均失败时抛出 [UpstreamResolutionException]。
 *
 * [context] 用于构建 Cronet（HTTP/3）engine。
 * [vpnService] 为 null 时表示不在 VPN 通路内（如手动解析工具）：UDP 上游走普通 socket，
 * 不做 protect；VPN 运行时必须传入，否则 UDP 上游查询会被虚拟 DNS 路由回环捕获。
 */
class CompositeResolver(
    private val context: Context,
    private val vpnService: VpnService?,
    groups: List<DnsServerGroup>,
) : Closeable {
    private val httpClient = HttpDnsResolver.createClient()
    private val cronetExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val http3Resolvers = mutableListOf<Http3DnsResolver>()
    private val resolverGroups = groups
        .filter(DnsServerGroup::enabled)
        .map { group -> group.enabledServers.map(::createResolver) }
        .filter { it.isNotEmpty() }

    init {
        require(resolverGroups.isNotEmpty()) { "至少需要启用一个含有效上游的 DNS 分组" }
    }

    suspend fun resolve(query: ByteArray): UpstreamResolution {
        // 无定时器的空闲回收：借每次查询顺带检查，关闭长时间未用的 HTTP/3 engine。
        recycleIdleHttp3()
        val failures = mutableListOf<Throwable>()
        for (group in resolverGroups) {
            try {
                return raceGroup(group, query)
            } catch (exception: UpstreamResolutionException) {
                failures += exception.suppressedExceptions.ifEmpty { listOf(exception) }
            }
        }
        throw UpstreamResolutionException(failures)
    }

    /** 组内并发竞速：第一个成功立即返回，其余取消。 */
    private suspend fun raceGroup(
        group: List<DnsResolver>,
        query: ByteArray,
    ): UpstreamResolution = coroutineScope {
        val results = Channel<Result<UpstreamResolution>>(group.size)
        val requests = group.map { resolver ->
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
            repeat(group.size) {
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

    private fun recycleIdleHttp3() {
        if (http3Resolvers.isEmpty()) return
        http3Resolvers.forEach { it.recycleIfIdle(HTTP3_IDLE_TIMEOUT_MS) }
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
                context,
                cronetExecutor,
                config.host,
                config.port,
                config.path,
            ).also(http3Resolvers::add)
        }
    }
}

private const val HTTP3_IDLE_TIMEOUT_MS = 5 * 60 * 1000L

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
