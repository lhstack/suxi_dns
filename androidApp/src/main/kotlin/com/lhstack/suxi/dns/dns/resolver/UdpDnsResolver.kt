package com.lhstack.suxi.dns.dns.resolver

import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * UDP DNS 上游。
 *
 * [vpnService] 为 null 时表示不在 VPN 通路内（如手动解析工具），
 * 此时普通 socket 直接发送，不需要 protect；VPN 运行时必须传入并 protect，
 * 否则上游查询会被虚拟 DNS 路由回环捕获。
 */
class UdpDnsResolver(
    private val vpnService: VpnService?,
    private val host: String,
    private val port: Int,
) : DnsResolver {
    override val description: String = "udp://$host:$port"

    override suspend fun resolve(query: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        DatagramSocket(null).use { socket ->
            // Protect before bind/connect so upstream traffic is never captured by the VPN TUN.
            if (vpnService != null) {
                check(vpnService.protect(socket)) { "无法保护 UDP 上游套接字，可能被 VPN 回环捕获" }
            }
            socket.bind(null)
            socket.soTimeout = TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(host, port))
            socket.send(DatagramPacket(query, query.size))

            val buffer = ByteArray(MAX_DNS_MESSAGE_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val response = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            validateDnsResponse(query, response)
            response
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000
        const val MAX_DNS_MESSAGE_SIZE = 65_535
    }
}
