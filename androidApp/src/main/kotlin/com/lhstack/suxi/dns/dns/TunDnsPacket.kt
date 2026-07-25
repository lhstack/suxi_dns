package com.lhstack.suxi.dns.dns

/** 统一的 TUN 上 DNS 查询抽象，便于 IPv4/IPv6 共用处理逻辑。 */
sealed class TunDnsPacket {
    abstract val dnsMessage: ByteArray
    abstract fun createResponse(responseMessage: ByteArray): ByteArray
    abstract fun createServFailResponse(): ByteArray

    data class V4(val packet: Ipv4UdpDnsPacket) : TunDnsPacket() {
        override val dnsMessage: ByteArray get() = packet.dnsMessage
        override fun createResponse(responseMessage: ByteArray): ByteArray =
            packet.createResponse(responseMessage)
        override fun createServFailResponse(): ByteArray = packet.createServFailResponse()
    }

    data class V6(val packet: Ipv6UdpDnsPacket) : TunDnsPacket() {
        override val dnsMessage: ByteArray get() = packet.dnsMessage
        override fun createResponse(responseMessage: ByteArray): ByteArray =
            packet.createResponse(responseMessage)
        override fun createServFailResponse(): ByteArray = packet.createServFailResponse()
    }

    companion object {
        fun parse(
            raw: ByteArray,
            ipv4Dns: ByteArray,
            ipv6Dns: ByteArray?,
        ): TunDnsPacket? {
            if (raw.isEmpty()) return null
            val version = raw[0].toInt() ushr 4 and 0x0F
            return when (version) {
                4 -> Ipv4UdpDnsPacket.parse(raw, ipv4Dns)?.let(TunDnsPacket::V4)
                6 -> {
                    if (ipv6Dns == null) null
                    else Ipv6UdpDnsPacket.parse(raw, ipv6Dns)?.let(TunDnsPacket::V6)
                }
                else -> null
            }
        }
    }
}
