package com.lhstack.suxi.dns.dns

data class Ipv4UdpDnsPacket(
    val ipHeader: ByteArray,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val dnsMessage: ByteArray,
) {
    fun createResponse(responseMessage: ByteArray): ByteArray {
        require(responseMessage.size <= MAX_DNS_MESSAGE_SIZE) { "DNS 响应过大" }
        val ipHeaderLength = ipHeader.size
        val udpLength = UDP_HEADER_SIZE + responseMessage.size
        val result = ByteArray(ipHeaderLength + udpLength)
        ipHeader.copyInto(result, endIndex = ipHeaderLength)
        destinationAddress.copyInto(result, destinationOffset = IPV4_SOURCE_OFFSET)
        sourceAddress.copyInto(result, destinationOffset = IPV4_DESTINATION_OFFSET)
        putUnsignedShort(result, IPV4_TOTAL_LENGTH_OFFSET, result.size)
        putUnsignedShort(result, IPV4_CHECKSUM_OFFSET, 0)

        val udpOffset = ipHeaderLength
        putUnsignedShort(result, udpOffset, destinationPort)
        putUnsignedShort(result, udpOffset + 2, sourcePort)
        putUnsignedShort(result, udpOffset + 4, udpLength)
        putUnsignedShort(result, udpOffset + 6, 0)
        responseMessage.copyInto(result, destinationOffset = udpOffset + UDP_HEADER_SIZE)

        putUnsignedShort(result, IPV4_CHECKSUM_OFFSET, internetChecksum(result, 0, ipHeaderLength))
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
        fun parse(packet: ByteArray, dnsAddress: ByteArray): Ipv4UdpDnsPacket? {
            if (packet.size < MIN_IPV4_UDP_PACKET_SIZE) return null
            val version = packet[0].toInt() ushr 4 and 0x0F
            if (version != IPV4_VERSION) return null
            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            if (ipHeaderLength < MIN_IPV4_HEADER_SIZE || packet.size < ipHeaderLength + UDP_HEADER_SIZE) {
                return null
            }
            val totalLength = unsignedShort(packet, IPV4_TOTAL_LENGTH_OFFSET)
            if (totalLength > packet.size || totalLength < ipHeaderLength + UDP_HEADER_SIZE) return null
            if (packet[IPV4_PROTOCOL_OFFSET].toInt() and 0xFF != UDP_PROTOCOL) return null
            if (unsignedShort(packet, IPV4_FRAGMENT_OFFSET) and IPV4_FRAGMENT_MASK != 0) return null

            val sourceAddress = packet.copyOfRange(IPV4_SOURCE_OFFSET, IPV4_SOURCE_OFFSET + IPV4_ADDRESS_SIZE)
            val destinationAddress = packet.copyOfRange(
                IPV4_DESTINATION_OFFSET,
                IPV4_DESTINATION_OFFSET + IPV4_ADDRESS_SIZE,
            )
            if (!destinationAddress.contentEquals(dnsAddress)) return null

            val udpOffset = ipHeaderLength
            val destinationPort = unsignedShort(packet, udpOffset + 2)
            if (destinationPort != DNS_PORT) return null
            val udpLength = unsignedShort(packet, udpOffset + 4)
            if (udpLength < UDP_HEADER_SIZE || udpOffset + udpLength > totalLength) return null
            val dnsMessage = packet.copyOfRange(udpOffset + UDP_HEADER_SIZE, udpOffset + udpLength)
            if (dnsMessage.size < DNS_HEADER_SIZE) return null

            return Ipv4UdpDnsPacket(
                ipHeader = packet.copyOfRange(0, ipHeaderLength),
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = unsignedShort(packet, udpOffset),
                destinationPort = destinationPort,
                dnsMessage = dnsMessage,
            )
        }

        private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        private fun putUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }

        private fun internetChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
            var sum = 0L
            var index = offset
            val end = offset + length
            while (index + 1 < end) {
                sum += unsignedShort(bytes, index).toLong()
                index += 2
            }
            if (index < end) sum += (bytes[index].toInt() and 0xFF shl 8).toLong()
            while (sum ushr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            return sum.inv().toInt() and 0xFFFF
        }

        private const val IPV4_VERSION = 4
        private const val UDP_PROTOCOL = 17
        private const val DNS_PORT = 53
        private const val DNS_HEADER_SIZE = 12
        private const val DNS_RESPONSE_FLAG = 0x80
        private const val DNS_RCODE_CLEAR_MASK = 0xF0
        private const val DNS_SERVFAIL = 2
        private const val DNS_ANSWER_COUNT_OFFSET = 6
        private const val MIN_IPV4_HEADER_SIZE = 20
        private const val UDP_HEADER_SIZE = 8
        private const val MIN_IPV4_UDP_PACKET_SIZE = MIN_IPV4_HEADER_SIZE + UDP_HEADER_SIZE
        private const val IPV4_TOTAL_LENGTH_OFFSET = 2
        private const val IPV4_FRAGMENT_OFFSET = 6
        private const val IPV4_PROTOCOL_OFFSET = 9
        private const val IPV4_CHECKSUM_OFFSET = 10
        private const val IPV4_SOURCE_OFFSET = 12
        private const val IPV4_DESTINATION_OFFSET = 16
        private const val IPV4_ADDRESS_SIZE = 4
        private const val IPV4_FRAGMENT_MASK = 0x3FFF
        private const val MAX_DNS_MESSAGE_SIZE = 65_535
    }
}
