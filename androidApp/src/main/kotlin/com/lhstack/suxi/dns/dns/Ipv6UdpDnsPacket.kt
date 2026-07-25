package com.lhstack.suxi.dns.dns

/**
 * IPv6 + UDP DNS 报文（固定 40 字节 IPv6 头 + 8 字节 UDP 头）。
 * 不处理扩展头；DNS 查询通常无扩展头。
 */
data class Ipv6UdpDnsPacket(
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val dnsMessage: ByteArray,
) {
    fun createResponse(responseMessage: ByteArray): ByteArray {
        require(responseMessage.size <= MAX_DNS_MESSAGE_SIZE) { "DNS 响应过大" }
        val payloadLength = UDP_HEADER_SIZE + responseMessage.size
        val result = ByteArray(IPV6_HEADER_SIZE + payloadLength)

        // Version=6, Traffic Class=0, Flow Label=0
        result[0] = 0x60
        putUnsignedShort(result, IPV6_PAYLOAD_LENGTH_OFFSET, payloadLength)
        result[IPV6_NEXT_HEADER_OFFSET] = UDP_PROTOCOL.toByte()
        result[IPV6_HOP_LIMIT_OFFSET] = 64
        // 交换地址：响应从 DNS 回到客户端
        destinationAddress.copyInto(result, destinationOffset = IPV6_SOURCE_OFFSET)
        sourceAddress.copyInto(result, destinationOffset = IPV6_DESTINATION_OFFSET)

        val udpOffset = IPV6_HEADER_SIZE
        putUnsignedShort(result, udpOffset, destinationPort)
        putUnsignedShort(result, udpOffset + 2, sourcePort)
        putUnsignedShort(result, udpOffset + 4, payloadLength)
        // IPv6 UDP 校验和可选为 0（部分实现接受）；为兼容性计算伪头校验和
        putUnsignedShort(result, udpOffset + 6, 0)
        responseMessage.copyInto(result, destinationOffset = udpOffset + UDP_HEADER_SIZE)
        putUnsignedShort(
            result,
            udpOffset + 6,
            udpChecksum(result, destinationAddress, sourceAddress, udpOffset, payloadLength),
        )
        return result
    }

    fun createServFailResponse(): ByteArray {
        require(dnsMessage.size >= DNS_HEADER_SIZE) { "DNS 查询报文短于报文头" }
        val response = dnsMessage.copyOf()
        response[2] = (response[2].toInt() or DNS_RESPONSE_FLAG).toByte()
        response[3] = ((response[3].toInt() and DNS_RCODE_CLEAR_MASK) or DNS_SERVFAIL).toByte()
        for (index in DNS_ANSWER_COUNT_OFFSET until DNS_HEADER_SIZE) {
            response[index] = 0
        }
        return createResponse(response)
    }

    companion object {
        fun parse(packet: ByteArray, dnsAddress: ByteArray): Ipv6UdpDnsPacket? {
            if (packet.size < MIN_IPV6_UDP_PACKET_SIZE) return null
            val version = packet[0].toInt() ushr 4 and 0x0F
            if (version != IPV6_VERSION) return null
            if (packet[IPV6_NEXT_HEADER_OFFSET].toInt() and 0xFF != UDP_PROTOCOL) return null

            val payloadLength = unsignedShort(packet, IPV6_PAYLOAD_LENGTH_OFFSET)
            if (payloadLength < UDP_HEADER_SIZE || IPV6_HEADER_SIZE + payloadLength > packet.size) {
                return null
            }

            val sourceAddress = packet.copyOfRange(IPV6_SOURCE_OFFSET, IPV6_SOURCE_OFFSET + IPV6_ADDRESS_SIZE)
            val destinationAddress = packet.copyOfRange(
                IPV6_DESTINATION_OFFSET,
                IPV6_DESTINATION_OFFSET + IPV6_ADDRESS_SIZE,
            )
            if (!destinationAddress.contentEquals(dnsAddress)) return null

            val udpOffset = IPV6_HEADER_SIZE
            val destinationPort = unsignedShort(packet, udpOffset + 2)
            if (destinationPort != DNS_PORT) return null
            val udpLength = unsignedShort(packet, udpOffset + 4)
            if (udpLength < UDP_HEADER_SIZE || udpLength > payloadLength) return null
            val dnsMessage = packet.copyOfRange(udpOffset + UDP_HEADER_SIZE, udpOffset + udpLength)
            if (dnsMessage.size < DNS_HEADER_SIZE) return null

            return Ipv6UdpDnsPacket(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = unsignedShort(packet, udpOffset),
                destinationPort = destinationPort,
                dnsMessage = dnsMessage,
            )
        }

        private fun udpChecksum(
            packet: ByteArray,
            src: ByteArray,
            dst: ByteArray,
            udpOffset: Int,
            udpLength: Int,
        ): Int {
            var sum = 0L
            // 伪头：src(16) + dst(16) + udpLength(4) + nextHeader=17
            for (i in 0 until 16 step 2) {
                sum += ((src[i].toInt() and 0xFF) shl 8 or (src[i + 1].toInt() and 0xFF)).toLong()
                sum += ((dst[i].toInt() and 0xFF) shl 8 or (dst[i + 1].toInt() and 0xFF)).toLong()
            }
            sum += udpLength.toLong()
            sum += UDP_PROTOCOL.toLong()

            var index = udpOffset
            val end = udpOffset + udpLength
            while (index + 1 < end) {
                sum += unsignedShort(packet, index).toLong()
                index += 2
            }
            if (index < end) sum += (packet[index].toInt() and 0xFF shl 8).toLong()
            while (sum ushr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            val result = sum.inv().toInt() and 0xFFFF
            return if (result == 0) 0xFFFF else result
        }

        private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        private fun putUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }

        private const val IPV6_VERSION = 6
        private const val UDP_PROTOCOL = 17
        private const val DNS_PORT = 53
        private const val DNS_HEADER_SIZE = 12
        private const val DNS_RESPONSE_FLAG = 0x80
        private const val DNS_RCODE_CLEAR_MASK = 0xF0
        private const val DNS_SERVFAIL = 2
        private const val DNS_ANSWER_COUNT_OFFSET = 6
        private const val IPV6_HEADER_SIZE = 40
        private const val UDP_HEADER_SIZE = 8
        private const val MIN_IPV6_UDP_PACKET_SIZE = IPV6_HEADER_SIZE + UDP_HEADER_SIZE + DNS_HEADER_SIZE
        private const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
        private const val IPV6_NEXT_HEADER_OFFSET = 6
        private const val IPV6_HOP_LIMIT_OFFSET = 7
        private const val IPV6_SOURCE_OFFSET = 8
        private const val IPV6_DESTINATION_OFFSET = 24
        private const val IPV6_ADDRESS_SIZE = 16
        private const val MAX_DNS_MESSAGE_SIZE = 65_535
    }
}
