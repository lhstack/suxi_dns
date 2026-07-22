package com.lhstack.suxi.dns.dns

/** 构造标准单问题 DNS 查询报文（用于 CNAME 跟随等）。 */
fun buildDnsQuery(name: String, type: Int, idHigh: Byte, idLow: Byte): ByteArray {
    val labels = normalizeDnsName(name).split('.').filter { it.isNotEmpty() }
    val qnameSize = labels.sumOf { 1 + it.length } + 1
    val message = ByteArray(DNS_HEADER_SIZE + qnameSize + 4)
    message[0] = idHigh
    message[1] = idLow
    message[2] = 0x01 // RD
    message[5] = 0x01 // QDCOUNT = 1
    var offset = DNS_HEADER_SIZE
    for (label in labels) {
        val bytes = label.toByteArray(Charsets.US_ASCII)
        require(bytes.size in 1..63) { "DNS 标签无效: $label" }
        message[offset++] = bytes.size.toByte()
        bytes.copyInto(message, offset)
        offset += bytes.size
    }
    message[offset++] = 0
    message[offset++] = (type ushr 8).toByte()
    message[offset++] = type.toByte()
    message[offset++] = 0
    message[offset] = 1 // IN
    return message
}

/** 从响应中提取答案段，转为可拼接到本地响应的 [LocalDnsAnswer]。 */
fun extractAnswerRecords(response: ByteArray): List<LocalDnsAnswer> {
    if (response.size < DNS_HEADER_SIZE) return emptyList()
    val qdCount = unsignedShort(response, 4)
    val anCount = unsignedShort(response, 6)
    if (anCount == 0) return emptyList()
    var offset = DNS_HEADER_SIZE
    repeat(qdCount) {
        offset = skipName(response, offset) ?: return emptyList()
        if (offset + 4 > response.size) return emptyList()
        offset += 4
    }
    val answers = ArrayList<LocalDnsAnswer>(anCount)
    repeat(anCount) {
        val nameStart = offset
        offset = skipName(response, offset) ?: return answers
        val ownerName = readNameAt(response, nameStart) ?: return answers
        if (offset + 10 > response.size) return answers
        val type = unsignedShort(response, offset)
        val ttl = unsignedInt(response, offset + 4)
        val rdLength = unsignedShort(response, offset + 8)
        offset += 10
        if (offset + rdLength > response.size) return answers
        val rdata = response.copyOfRange(offset, offset + rdLength)
        offset += rdLength
        answers += LocalDnsAnswer(
            name = ownerName,
            type = type,
            ttlSeconds = ttl,
            rdata = rdata,
        )
    }
    return answers
}

private fun readNameAt(message: ByteArray, start: Int): String? {
    val labels = mutableListOf<String>()
    var offset = start
    var jumps = 0
    while (offset < message.size && jumps < 16) {
        val length = message[offset].toInt() and 0xFF
        when {
            length == 0 -> return labels.joinToString(".").ifEmpty { "." }
            length and 0xC0 == 0xC0 -> {
                if (offset + 1 >= message.size) return null
                val pointer = ((length and 0x3F) shl 8) or (message[offset + 1].toInt() and 0xFF)
                offset = pointer
                jumps++
            }
            else -> {
                offset += 1
                if (offset + length > message.size) return null
                labels += String(message, offset, length, Charsets.US_ASCII)
                offset += length
            }
        }
    }
    return null
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

private const val DNS_HEADER_SIZE = 12
