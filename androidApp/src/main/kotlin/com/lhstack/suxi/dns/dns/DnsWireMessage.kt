package com.lhstack.suxi.dns.dns

import com.lhstack.suxi.dns.model.LocalDnsRecord
import com.lhstack.suxi.dns.model.LocalRecordType
import java.net.InetAddress

data class DnsQuestionInfo(
    val name: String,
    val type: Int,
    val clazz: Int,
    val endOffset: Int,
)

fun parseDnsQuestion(message: ByteArray): DnsQuestionInfo? {
    if (message.size < DNS_HEADER_SIZE + 5) return null
    val labels = mutableListOf<String>()
    var offset = DNS_HEADER_SIZE
    while (offset < message.size) {
        val length = message[offset].toInt() and 0xFF
        when {
            length == 0 -> {
                offset += 1
                break
            }
            length and 0xC0 != 0 -> return null
            else -> {
                offset += 1
                if (offset + length > message.size) return null
                labels += String(message, offset, length, Charsets.US_ASCII)
                offset += length
            }
        }
    }
    if (offset + 4 > message.size) return null
    val type = unsignedShort(message, offset)
    val clazz = unsignedShort(message, offset + 2)
    return DnsQuestionInfo(
        name = labels.joinToString(".").ifEmpty { "." },
        type = type,
        clazz = clazz,
        endOffset = offset + 4,
    )
}

/**
 * 基于原始查询构造响应。
 * [rcode]：0=NOERROR，3=NXDOMAIN，2=SERVFAIL。
 * [answers] 为空时仅返回指定 rcode。
 */
fun buildDnsResponse(
    query: ByteArray,
    answers: List<LocalDnsAnswer> = emptyList(),
    rcode: Int = RCODE_NOERROR,
    authoritative: Boolean = true,
): ByteArray {
    require(query.size >= DNS_HEADER_SIZE) { "DNS 查询报文短于报文头" }
    val question = parseDnsQuestion(query)
        ?: error("无法解析 DNS 问题段")

    val out = ArrayList<Byte>(query.size + 64)
    // Header
    out += query[0]
    out += query[1]
    var flagsHigh = (query[2].toInt() and 0x01) // 保留 RD
    flagsHigh = flagsHigh or 0x80 // QR
    if (authoritative) flagsHigh = flagsHigh or 0x04 // AA
    out += flagsHigh.toByte()
    out += ((query[3].toInt() and 0x70) or (rcode and 0x0F)).toByte()
    // QDCOUNT
    out += query[4]
    out += query[5]
    // ANCOUNT
    putShort(out, answers.size)
    // NSCOUNT / ARCOUNT = 0
    putShort(out, 0)
    putShort(out, 0)
    // Question section copy
    for (i in DNS_HEADER_SIZE until question.endOffset) {
        out += query[i]
    }
    // Answers
    for (answer in answers) {
        writeName(out, answer.name)
        putShort(out, answer.type)
        putShort(out, CLASS_IN)
        putInt(out, answer.ttlSeconds)
        putShort(out, answer.rdata.size)
        answer.rdata.forEach { out += it }
    }
    return out.toByteArray()
}

data class LocalDnsAnswer(
    val name: String,
    val type: Int,
    val ttlSeconds: Int,
    val rdata: ByteArray,
)

fun localRecordToAnswers(record: LocalDnsRecord, queryName: String): List<LocalDnsAnswer> {
    val owner = normalizeDnsName(queryName).ifEmpty { normalizeDnsName(record.name) }
    val ttl = record.ttlSeconds.coerceAtLeast(0)
    return when (record.type) {
        LocalRecordType.A -> listOf(
            LocalDnsAnswer(owner, TYPE_A, ttl, ipv4Rdata(record.value)),
        )
        LocalRecordType.AAAA -> listOf(
            LocalDnsAnswer(owner, TYPE_AAAA, ttl, ipv6Rdata(record.value)),
        )
        LocalRecordType.CNAME -> listOf(
            LocalDnsAnswer(owner, TYPE_CNAME, ttl, nameRdata(record.value)),
        )
    }
}

private fun ipv4Rdata(ip: String): ByteArray {
    val addr = InetAddress.getByName(ip).address
    require(addr.size == 4) { "不是 IPv4: $ip" }
    return addr
}

private fun ipv6Rdata(ip: String): ByteArray {
    val addr = InetAddress.getByName(ip).address
    require(addr.size == 16) { "不是 IPv6: $ip" }
    return addr
}

private fun nameRdata(name: String): ByteArray {
    val out = ArrayList<Byte>()
    writeName(out, name)
    return out.toByteArray()
}

private fun writeName(out: MutableList<Byte>, name: String) {
    val normalized = name.trim().trimEnd('.')
    if (normalized.isEmpty() || normalized == ".") {
        out += 0
        return
    }
    for (label in normalized.split('.')) {
        val bytes = label.toByteArray(Charsets.US_ASCII)
        require(bytes.size in 1..63) { "DNS 标签长度无效: $label" }
        out += bytes.size.toByte()
        bytes.forEach { out += it }
    }
    out += 0
}

private fun putShort(out: MutableList<Byte>, value: Int) {
    out += (value ushr 8).toByte()
    out += value.toByte()
}

private fun putInt(out: MutableList<Byte>, value: Int) {
    out += (value ushr 24).toByte()
    out += (value ushr 16).toByte()
    out += (value ushr 8).toByte()
    out += value.toByte()
}

private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

const val TYPE_A = 1
const val TYPE_CNAME = 5
const val TYPE_AAAA = 28
const val CLASS_IN = 1
const val RCODE_NOERROR = 0
const val RCODE_SERVFAIL = 2
const val RCODE_NXDOMAIN = 3
private const val DNS_HEADER_SIZE = 12
