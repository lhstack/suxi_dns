package com.lhstack.suxi.dns.dns

/**
 * 将 DNS 响应报文解析为结构化的 [ManualDnsRecord] 列表，供手动解析页展示。
 *
 * 与 [extractAnswerRecords] 不同：本解析器针对完整报文工作，能正确解析 rdata 内部的
 * 域名压缩指针（CNAME/NS/PTR/MX/SOA/SRV 普遍使用压缩）。
 * 只提取与请求类型匹配的答案记录；其余（如伴随的 CNAME）也一并保留以便展示。
 */
object ManualDnsParser {
    private const val DNS_HEADER_SIZE = 12

    /** 把响应报文解析为指定查询类型的结果记录列表。 */
    fun parse(response: ByteArray, queryType: ManualQueryType): List<ManualDnsRecord> {
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

        val records = ArrayList<ManualDnsRecord>(anCount)
        repeat(anCount) {
            val ownerStart = offset
            offset = skipName(response, offset) ?: return records
            val ownerName = readName(response, ownerStart) ?: return records
            if (offset + 10 > response.size) return records
            val type = unsignedShort(response, offset)
            val ttl = unsignedInt(response, offset + 4)
            val rdLength = unsignedShort(response, offset + 8)
            val rdataStart = offset + 10
            offset = rdataStart + rdLength
            if (offset > response.size) return records

            val manualType = wireTypeToManual(type)
            // 保留：与查询类型一致，或是 A/AAAA 查询中伴随返回的 CNAME。
            val keep = manualType == queryType ||
                (manualType == ManualQueryType.CNAME &&
                    (queryType == ManualQueryType.A || queryType == ManualQueryType.AAAA))
            if (manualType != null && keep) {
                val value = formatRdata(manualType, response, rdataStart, rdLength)
                records += ManualDnsRecord(ownerName, manualType, ttl, value)
            }
        }
        return records
    }

    /** 从响应中提取 rcode，无答案时用于展示原因。 */
    fun rcodeMessage(response: ByteArray): String? {
        if (response.size < DNS_HEADER_SIZE) return "响应报文无效"
        val anCount = unsignedShort(response, 6)
        if (anCount > 0) return null
        return when (response[3].toInt() and 0x0F) {
            0 -> "无匹配记录"
            1 -> "报文格式错误"
            2 -> "上游服务失败"
            3 -> "域名不存在 (NXDOMAIN)"
            5 -> "上游拒绝查询"
            else -> "无答案记录"
        }
    }

    private fun wireTypeToManual(type: Int): ManualQueryType? =
        ManualQueryType.entries.firstOrNull { it.wireType == type }

    private fun formatRdata(
        type: ManualQueryType,
        message: ByteArray,
        start: Int,
        length: Int,
    ): String {
        return when (type) {
            ManualQueryType.A -> formatIpv4(message, start, length)
            ManualQueryType.AAAA -> formatIpv6(message, start, length)
            ManualQueryType.CNAME,
            ManualQueryType.NS,
            ManualQueryType.PTR,
            -> readName(message, start) ?: "(无效名称)"

            ManualQueryType.TXT -> formatTxt(message, start, length)
            ManualQueryType.MX -> formatMx(message, start, length)
            ManualQueryType.SOA -> formatSoa(message, start, length)
            ManualQueryType.SRV -> formatSrv(message, start, length)
        }
    }

    private fun formatIpv4(message: ByteArray, start: Int, length: Int): String {
        if (length != 4 || start + 4 > message.size) return "(无效 A)"
        return (0 until 4).joinToString(".") { (message[start + it].toInt() and 0xFF).toString() }
    }

    private fun formatIpv6(message: ByteArray, start: Int, length: Int): String {
        if (length != 16 || start + 16 > message.size) return "(无效 AAAA)"
        val groups = (0 until 8).map { index ->
            val high = message[start + index * 2].toInt() and 0xFF
            val low = message[start + index * 2 + 1].toInt() and 0xFF
            (high shl 8) or low
        }
        return compressIpv6(groups)
    }

    /** RFC 5952 风格：把最长的连续 0 段压缩为 ::。 */
    private fun compressIpv6(groups: List<Int>): String {
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        var curLen = 0
        for (i in groups.indices) {
            if (groups[i] == 0) {
                if (curStart == -1) curStart = i
                curLen++
                if (curLen > bestLen) {
                    bestLen = curLen
                    bestStart = curStart
                }
            } else {
                curStart = -1
                curLen = 0
            }
        }
        if (bestLen < 2) {
            return groups.joinToString(":") { it.toString(16) }
        }
        val head = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
        val tail = (bestStart + bestLen until groups.size).joinToString(":") { groups[it].toString(16) }
        return "$head::$tail"
    }

    private fun formatTxt(message: ByteArray, start: Int, length: Int): String {
        val end = start + length
        if (end > message.size) return "(无效 TXT)"
        val parts = mutableListOf<String>()
        var offset = start
        while (offset < end) {
            val segLen = message[offset].toInt() and 0xFF
            offset += 1
            if (offset + segLen > end) break
            parts += String(message, offset, segLen, Charsets.UTF_8)
            offset += segLen
        }
        return parts.joinToString("")
    }

    private fun formatMx(message: ByteArray, start: Int, length: Int): String {
        if (length < 3 || start + 2 > message.size) return "(无效 MX)"
        val preference = unsignedShort(message, start)
        val exchange = readName(message, start + 2) ?: "?"
        return "$preference $exchange"
    }

    private fun formatSrv(message: ByteArray, start: Int, length: Int): String {
        if (length < 7 || start + 6 > message.size) return "(无效 SRV)"
        val priority = unsignedShort(message, start)
        val weight = unsignedShort(message, start + 2)
        val port = unsignedShort(message, start + 4)
        val target = readName(message, start + 6) ?: "?"
        return "优先级 $priority 权重 $weight 端口 $port 目标 $target"
    }

    private fun formatSoa(message: ByteArray, start: Int, length: Int): String {
        val end = start + length
        if (end > message.size) return "(无效 SOA)"
        var offset = start
        val primary = readName(message, offset) ?: return "(无效 SOA)"
        offset = skipName(message, offset) ?: return "(无效 SOA)"
        val admin = readName(message, offset) ?: return "(无效 SOA)"
        offset = skipName(message, offset) ?: return "(无效 SOA)"
        if (offset + 20 > message.size) return "$primary $admin"
        val serial = unsignedInt(message, offset)
        val refresh = unsignedInt(message, offset + 4)
        val retry = unsignedInt(message, offset + 8)
        val expire = unsignedInt(message, offset + 12)
        val minimum = unsignedInt(message, offset + 16)
        return "主服务器 $primary 管理邮箱 $admin 序列号 $serial " +
            "刷新 $refresh 重试 $retry 过期 $expire 最小TTL $minimum"
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
}
