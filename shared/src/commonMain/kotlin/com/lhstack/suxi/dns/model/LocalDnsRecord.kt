package com.lhstack.suxi.dns.model

import kotlinx.serialization.Serializable

@Serializable
enum class LocalRecordType(val displayName: String, val wireType: Int) {
    A("A", 1),
    AAAA("AAAA", 28),
    CNAME("CNAME", 5),
}

/**
 * 自定义域名解析记录。
 * [name] 支持完整域名或通配（如 `*.example.com`、`ads.*.net`）。
 */
@Serializable
data class LocalDnsRecord(
    val id: Long,
    val name: String,
    val type: LocalRecordType = LocalRecordType.A,
    val value: String,
    val ttlSeconds: Int = 300,
    val enabled: Boolean = true,
) {
    fun validate() {
        require(name.isNotBlank()) { "域名不能为空" }
        require(ttlSeconds in 0..2_147_483_647) { "TTL 必须为非负整数" }
        when (type) {
            LocalRecordType.A -> require(isIpv4(value)) { "A 记录值必须是 IPv4 地址" }
            LocalRecordType.AAAA -> require(isIpv6Literal(value)) { "AAAA 记录值必须是 IPv6 地址" }
            LocalRecordType.CNAME -> {
                require(value.isNotBlank()) { "CNAME 目标不能为空" }
                require(!value.contains(' ')) { "CNAME 目标格式无效" }
            }
        }
    }

    val displaySummary: String
        get() = "$name ${type.displayName} $value (TTL ${ttlSeconds}s)"
}

private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        val n = part.toIntOrNull() ?: return false
        n in 0..255 && part == n.toString()
    }
}

private fun isIpv6Literal(value: String): Boolean {
    if (value.isBlank() || !value.contains(':')) return false
    return value.all {
        it.isDigit() || it == ':' || it == '.' || it in 'a'..'f' || it in 'A'..'F'
    }
}
