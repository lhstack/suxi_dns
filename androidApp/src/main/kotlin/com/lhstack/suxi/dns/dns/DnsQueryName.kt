package com.lhstack.suxi.dns.dns

/** 从 DNS 报文中尽力提取查询域名，供内存日志使用。 */
fun extractDnsQueryName(message: ByteArray): String {
    if (message.size < DNS_HEADER_SIZE + 1) return "(无效请求)"
    var offset = DNS_HEADER_SIZE
    val labels = mutableListOf<String>()
    while (offset < message.size) {
        val length = message[offset].toInt() and 0xFF
        if (length == 0) break
        if (length and 0xC0 != 0) return labels.joinToString(".").ifEmpty { "(压缩名)" }
        offset += 1
        if (offset + length > message.size) return "(报文截断)"
        labels += String(message, offset, length, Charsets.US_ASCII)
        offset += length
    }
    return labels.joinToString(".").ifEmpty { "(根)" }
}

private const val DNS_HEADER_SIZE = 12
