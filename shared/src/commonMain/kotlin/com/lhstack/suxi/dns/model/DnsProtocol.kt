package com.lhstack.suxi.dns.model

import kotlinx.serialization.Serializable

@Serializable
enum class DnsProtocol(
    val displayName: String,
    val defaultPort: Int,
    val usesHttpPath: Boolean,
) {
    UDP("UDP", 53, false),
    HTTP("HTTP", 80, true),
    HTTPS("HTTPS", 443, true),
    HTTP3("HTTP/3", 443, true),
}
