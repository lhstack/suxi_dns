package com.lhstack.suxi.dns.dns

/**
 * Best-effort summary of DNS answer RDATA for in-memory logs.
 * Supports uncompressed names only; compressed owner names are skipped safely.
 */
fun summarizeDnsAnswers(response: ByteArray): String {
    if (response.size < DNS_HEADER_SIZE) return "(无效响应)"
    val answerCount = unsignedShort(response, 6)
    if (answerCount == 0) {
        val rcode = response[3].toInt() and 0x0F
        return when (rcode) {
            0 -> "无答案记录"
            1 -> "格式错误"
            2 -> "服务器失败"
            3 -> "域名不存在"
            5 -> "拒绝查询"
            else -> "无答案 (rcode=$rcode)"
        }
    }

    var offset = DNS_HEADER_SIZE
    // Skip question section (QDCOUNT).
    val questionCount = unsignedShort(response, 4)
    repeat(questionCount) {
        offset = skipName(response, offset) ?: return "${answerCount}条答案"
        if (offset + 4 > response.size) return "${answerCount}条答案"
        offset += 4 // QTYPE + QCLASS
    }

    val parts = mutableListOf<String>()
    repeat(answerCount) {
        offset = skipName(response, offset) ?: return joinOrCount(parts, answerCount)
        if (offset + 10 > response.size) return joinOrCount(parts, answerCount)
        val type = unsignedShort(response, offset)
        val rdLength = unsignedShort(response, offset + 8)
        offset += 10
        if (offset + rdLength > response.size) return joinOrCount(parts, answerCount)
        val rdata = response.copyOfRange(offset, offset + rdLength)
        offset += rdLength
        formatRdata(type, rdata)?.let(parts::add)
    }
    return if (parts.isEmpty()) {
        "${answerCount}条答案"
    } else {
        parts.joinToString(", ")
    }
}

private fun joinOrCount(parts: List<String>, answerCount: Int): String {
    return if (parts.isEmpty()) "${answerCount}条答案" else parts.joinToString(", ")
}

private fun formatRdata(type: Int, rdata: ByteArray): String? {
    return when (type) {
        TYPE_A -> {
            if (rdata.size != 4) return null
            rdata.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }

        TYPE_AAAA -> {
            if (rdata.size != 16) return null
            formatIpv6(rdata)
        }

        TYPE_CNAME, TYPE_NS, TYPE_PTR -> readName(rdata, 0) ?: typeLabel(type)

        TYPE_TXT -> {
            if (rdata.isEmpty()) return "TXT"
            val length = rdata[0].toInt() and 0xFF
            if (length + 1 > rdata.size) return "TXT"
            "TXT(${String(rdata, 1, length, Charsets.UTF_8)})"
        }

        TYPE_MX -> {
            if (rdata.size < 3) return "MX"
            val preference = unsignedShort(rdata, 0)
            val exchange = readName(rdata, 2) ?: "?"
            "MX($preference $exchange)"
        }

        else -> typeLabel(type)
    }
}

private fun typeLabel(type: Int): String = when (type) {
    TYPE_A -> "A"
    TYPE_NS -> "NS"
    TYPE_CNAME -> "CNAME"
    TYPE_SOA -> "SOA"
    TYPE_PTR -> "PTR"
    TYPE_MX -> "MX"
    TYPE_TXT -> "TXT"
    TYPE_AAAA -> "AAAA"
    TYPE_SRV -> "SRV"
    else -> "TYPE$type"
}

private fun formatIpv6(bytes: ByteArray): String {
    val groups = (0 until 8).map { index ->
        val high = bytes[index * 2].toInt() and 0xFF
        val low = bytes[index * 2 + 1].toInt() and 0xFF
        ((high shl 8) or low).toString(16)
    }
    return groups.joinToString(":")
}

private fun skipName(message: ByteArray, start: Int): Int? {
    var offset = start
    var jumps = 0
    while (offset < message.size) {
        val label = message[offset].toInt() and 0xFF
        when {
            label == 0 -> return offset + 1
            label and 0xC0 == 0xC0 -> {
                if (offset + 1 >= message.size) return null
                // Compressed pointer is always 2 bytes in the current sequence.
                return offset + 2
            }
            else -> {
                offset += 1 + label
                if (offset > message.size) return null
                if (++jumps > 64) return null
            }
        }
    }
    return null
}

private fun readName(message: ByteArray, start: Int): String? {
    val labels = mutableListOf<String>()
    var offset = start
    var jumps = 0
    while (offset < message.size) {
        val label = message[offset].toInt() and 0xFF
        when {
            label == 0 -> return labels.joinToString(".").ifEmpty { "." }
            label and 0xC0 == 0xC0 -> {
                if (offset + 1 >= message.size) return null
                val pointer = ((label and 0x3F) shl 8) or (message[offset + 1].toInt() and 0xFF)
                if (pointer >= message.size || ++jumps > 20) return null
                offset = pointer
            }
            else -> {
                offset += 1
                if (offset + label > message.size) return null
                labels += String(message, offset, label, Charsets.US_ASCII)
                offset += label
            }
        }
    }
    return null
}

private fun unsignedShort(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

private const val DNS_HEADER_SIZE = 12
private const val TYPE_NS = 2
private const val TYPE_SOA = 6
private const val TYPE_PTR = 12
private const val TYPE_MX = 15
private const val TYPE_TXT = 16
private const val TYPE_SRV = 33
