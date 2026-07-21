package com.lhstack.suxi.dns.dns

/**
 * TLS 相关的上游端点信息。
 * 当用户配置的是 IP 时，需要把连接地址与证书/SNI 主机名分开处理。
 */
data class TlsEndpoint(
    /** 实际连接使用的主机或 IP。 */
    val connectHost: String,
    /** TLS SNI / HTTP Host 名称。 */
    val serverName: String,
    /** 连接目标是否为字面量 IP。 */
    val connectHostIsIp: Boolean,
)

fun resolveTlsEndpoint(host: String): TlsEndpoint {
    val trimmed = host.trim()
    val isIp = isIpLiteral(trimmed)
    if (!isIp) {
        return TlsEndpoint(
            connectHost = trimmed,
            serverName = trimmed,
            connectHostIsIp = false,
        )
    }
    val mapped = wellKnownServerName(trimmed)
    return TlsEndpoint(
        connectHost = trimmed,
        serverName = mapped ?: trimmed,
        connectHostIsIp = true,
    )
}

/**
 * 仅做字面量判定，不触发 DNS 查询（VPN 内避免 bootstrap 死锁）。
 */
fun isIpLiteral(host: String): Boolean {
    if (host.matches(IPV4_REGEX)) return true
    // 粗判 IPv6 字面量（含压缩写法），不解析网络。
    if (host.contains(':') && host.all {
            it.isDigit() || it == ':' || it == '.' || it in 'a'..'f' || it in 'A'..'F'
        }
    ) {
        return true
    }
    return false
}

/**
 * 常见公共 DNS 的 IP → 证书主机名映射（DoQ/DoH TLS SNI）。
 * 连接仍优先走用户填写的 IP；域名场景则由底层网络解析。
 */
fun wellKnownServerName(ip: String): String? = when (ip) {
    "223.5.5.5", "223.6.6.6" -> "dns.alidns.com"
    "8.8.8.8", "8.8.4.4" -> "dns.google"
    "1.1.1.1", "1.0.0.1" -> "cloudflare-dns.com"
    "9.9.9.9", "149.112.112.112" -> "dns.quad9.net"
    "208.67.222.222", "208.67.220.220" -> "dns.opendns.com"
    else -> null
}

private val IPV4_REGEX = Regex(
    "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
)
