package com.lhstack.suxi.dns.dns.resolver

interface DnsResolver {
    val description: String

    suspend fun resolve(query: ByteArray): ByteArray
}

fun validateDnsResponse(query: ByteArray, response: ByteArray) {
    require(query.size >= DNS_HEADER_SIZE) { "DNS 查询报文短于报文头" }
    require(response.size >= DNS_HEADER_SIZE) { "DNS 响应报文短于报文头" }
    require(query[0] == response[0] && query[1] == response[1]) {
        "DNS 响应事务 ID 与查询不一致"
    }
    require(response[2].toInt() and DNS_RESPONSE_FLAG != 0) {
        "上游返回的是 DNS 查询而不是响应"
    }
}

private const val DNS_HEADER_SIZE = 12
private const val DNS_RESPONSE_FLAG = 0x80
