package com.lhstack.suxi.dns.model

import kotlinx.serialization.Serializable

/**
 * DNS 上游分组。
 *
 * 组内的启用上游并发竞速，取第一个通过校验的成功响应；
 * 组之间按列表顺序 fallback：当前组全部失败才尝试下一组。
 * 组顺序即 fallback 顺序，由用户在界面上拖拽调整。
 */
@Serializable
data class DnsServerGroup(
    val id: Long,
    val name: String = "",
    val enabled: Boolean = true,
    val servers: List<DnsServerConfig> = emptyList(),
) {
    val enabledServers: List<DnsServerConfig>
        get() = servers.filter(DnsServerConfig::enabled)

    /** 启动前校验：启用的组必须至少有一个启用且合法的上游。 */
    fun validate() {
        require(enabledServers.isNotEmpty()) {
            "分组「${displayName}」需要至少启用一个 DNS 上游"
        }
        enabledServers.forEach(DnsServerConfig::validate)
    }

    val displayName: String
        get() = name.ifBlank { "分组 $id" }
}
