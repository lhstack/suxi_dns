package com.lhstack.suxi.dns.model

import kotlinx.serialization.Serializable

@Serializable
data class DnsServerConfig(
    val id: Long,
    val protocol: DnsProtocol,
    val host: String,
    val port: Int,
    val path: String = "/dns-query",
    val enabled: Boolean = true,
) {
    val displayAddress: String
        get() = when (protocol) {
            DnsProtocol.UDP -> "udp://$host:$port"
            DnsProtocol.HTTP -> "http://$host:$port$path"
            DnsProtocol.HTTPS -> "https://$host:$port$path"
            DnsProtocol.HTTP3 -> "https://$host:$port$path (HTTP/3)"
        }

    fun validate() {
        require(host.isNotBlank()) { "DNS 服务器主机名不能为空" }
        require(port in 1..65535) { "DNS 服务器端口必须在 1 到 65535 之间" }
        if (protocol.usesHttpPath) {
            require(path.startsWith('/')) { "DNS HTTP 路径必须以 / 开头" }
        }
    }
}
