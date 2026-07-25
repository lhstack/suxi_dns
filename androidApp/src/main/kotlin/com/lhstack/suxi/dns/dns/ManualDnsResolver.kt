package com.lhstack.suxi.dns.dns

import android.content.Context
import com.lhstack.suxi.dns.dns.resolver.CompositeResolver
import com.lhstack.suxi.dns.model.DnsServerGroup
import java.net.InetAddress
import kotlin.random.Random

/**
 * 手动解析工具的查询执行器。
 *
 * 不经过 VPN TUN：以 [CompositeResolver] 复用「分组内并发竞速、组间 fallback」的逻辑，
 * 但 vpnService 传 null，UDP 走普通 socket。因此无论 VPN 是否运行都可用。
 *
 * 每个查询类型独立发送一个请求，互不影响；任一类型失败只记录该类型的错误，
 * 不影响其他类型的结果。
 */
class ManualDnsResolver(
    private val context: Context,
) {
    /**
     * 对 [input] 按 [types] 逐类型解析，使用 [group] 作为唯一上游分组。
     *
     * - 正向类型（A/AAAA/CNAME/MX/TXT/NS/SOA/SRV）：[input] 作为域名查询。
     * - PTR：[input] 必须是 IPv4/IPv6 字面量，转换为 in-addr.arpa / ip6.arpa 反查名。
     */
    suspend fun resolve(
        input: String,
        types: List<ManualQueryType>,
        group: DnsServerGroup,
    ): List<ManualQueryResult> {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "请输入域名或 IP 地址" }
        require(types.isNotEmpty()) { "请至少选择一种解析类型" }
        findManualTypeConflict(types.toSet())?.let { error(it) }

        val resolver = CompositeResolver(context, null, listOf(group))
        try {
            return types.map { type -> resolveOne(resolver, trimmed, type) }
        } finally {
            resolver.close()
        }
    }

    private suspend fun resolveOne(
        resolver: CompositeResolver,
        input: String,
        type: ManualQueryType,
    ): ManualQueryResult {
        val queryName = if (type.isReverse) reverseName(input) else normalizeDnsName(input)
        val id = Random.nextInt(0, 0x10000)
        val query = buildDnsQuery(queryName, type.wireType, (id ushr 8).toByte(), id.toByte())
        val startedAt = System.currentTimeMillis()
        return try {
            val resolution = resolver.resolve(query)
            val elapsed = System.currentTimeMillis() - startedAt
            val records = ManualDnsParser.parse(resolution.response, type)
            ManualQueryResult(
                type = type,
                upstream = resolution.upstream,
                elapsedMs = elapsed,
                records = records,
                rcodeMessage = if (records.isEmpty()) {
                    ManualDnsParser.rcodeMessage(resolution.response)
                } else {
                    null
                },
                error = null,
            )
        } catch (exception: Exception) {
            ManualQueryResult(
                type = type,
                upstream = "",
                elapsedMs = System.currentTimeMillis() - startedAt,
                records = emptyList(),
                rcodeMessage = null,
                error = exception.message ?: exception::class.simpleName ?: "解析失败",
            )
        }
    }

    /** 把 IP 字面量转换为反向解析域名（in-addr.arpa / ip6.arpa）。 */
    private fun reverseName(ip: String): String {
        val address = try {
            InetAddress.getByName(ip.trim())
        } catch (_: Exception) {
            error("PTR 反向解析需要有效的 IP 地址：$ip")
        }
        val bytes = address.address
        return when (bytes.size) {
            4 -> bytes.reversed().joinToString(".", postfix = ".in-addr.arpa") {
                (it.toInt() and 0xFF).toString()
            }
            16 -> bytes.reversed().joinToString(".", postfix = ".ip6.arpa") { byte ->
                val v = byte.toInt() and 0xFF
                "${(v and 0x0F).toString(16)}.${(v ushr 4).toString(16)}"
            }
            else -> error("无法识别的 IP 地址：$ip")
        }
    }
}
