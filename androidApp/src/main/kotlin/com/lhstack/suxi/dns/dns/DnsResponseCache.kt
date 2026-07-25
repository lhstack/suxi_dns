package com.lhstack.suxi.dns.dns

import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内 DNS 响应缓存，按「规范化域名 + 查询类型」缓存完整响应报文。
 * 用答案区最小 TTL（至少 [MIN_TTL_SECONDS]）控制过期，避免重复上游往返。
 */
class DnsResponseCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Key(val name: String, val type: Int)

    private data class Entry(
        val response: ByteArray,
        val expireAtMs: Long,
    )

    private val map = ConcurrentHashMap<Key, Entry>()

    fun get(name: String, type: Int): ByteArray? {
        val key = Key(normalizeDnsName(name), type)
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() >= entry.expireAtMs) {
            map.remove(key, entry)
            return null
        }
        return entry.response.copyOf()
    }

    fun put(name: String, type: Int, response: ByteArray) {
        if (response.size < DNS_HEADER_SIZE) return
        // 不缓存失败应答（SERVFAIL 等），避免短时错误被放大
        val rcode = response[3].toInt() and 0x0F
        if (rcode != RCODE_NOERROR && rcode != RCODE_NXDOMAIN) return

        val ttl = extractMinAnswerTtlSeconds(response)?.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
            ?: if (rcode == RCODE_NXDOMAIN) NXDOMAIN_TTL_SECONDS else MIN_TTL_SECONDS

        val key = Key(normalizeDnsName(name), type)
        map[key] = Entry(
            response = response.copyOf(),
            expireAtMs = System.currentTimeMillis() + ttl * 1000L,
        )
        evictIfNeeded()
    }

    fun clear() {
        map.clear()
    }

    val size: Int get() = map.size

    private fun evictIfNeeded() {
        if (map.size <= maxEntries) return
        val now = System.currentTimeMillis()
        map.entries.removeIf { now >= it.value.expireAtMs }
        if (map.size <= maxEntries) return
        // 仍过多：删最早过期的若干项
        val overflow = map.size - maxEntries
        map.entries
            .sortedBy { it.value.expireAtMs }
            .take(overflow)
            .forEach { map.remove(it.key, it.value) }
    }

    private fun extractMinAnswerTtlSeconds(response: ByteArray): Int? {
        if (response.size < DNS_HEADER_SIZE) return null
        val qdCount = unsignedShort(response, 4)
        val anCount = unsignedShort(response, 6)
        if (anCount == 0) return null
        var offset = DNS_HEADER_SIZE
        repeat(qdCount) {
            offset = skipName(response, offset) ?: return null
            if (offset + 4 > response.size) return null
            offset += 4
        }
        var minTtl: Int? = null
        repeat(anCount) {
            offset = skipName(response, offset) ?: return minTtl
            if (offset + 10 > response.size) return minTtl
            val ttl = unsignedInt(response, offset + 4)
            val rdLength = unsignedShort(response, offset + 8)
            offset += 10 + rdLength
            if (offset > response.size) return minTtl
            minTtl = minTtl?.let { minOf(it, ttl) } ?: ttl
        }
        return minTtl
    }

    private fun skipName(message: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < message.size) {
            val label = message[offset].toInt() and 0xFF
            when {
                label == 0 -> return offset + 1
                label and 0xC0 == 0xC0 -> {
                    if (offset + 1 >= message.size) return null
                    return offset + 2
                }
                else -> {
                    offset += 1 + label
                    if (offset > message.size) return null
                }
            }
        }
        return null
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun unsignedInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private companion object {
        const val DNS_HEADER_SIZE = 12
        const val DEFAULT_MAX_ENTRIES = 512
        const val MIN_TTL_SECONDS = 30
        const val MAX_TTL_SECONDS = 600
        const val NXDOMAIN_TTL_SECONDS = 60
    }
}

/** 将缓存中的响应 ID 改成与当前查询一致。 */
fun rewriteDnsResponseId(template: ByteArray, query: ByteArray): ByteArray {
    require(template.size >= 2 && query.size >= 2)
    val out = template.copyOf()
    out[0] = query[0]
    out[1] = query[1]
    return out
}
