package com.lhstack.suxi.dns.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.lhstack.suxi.dns.dns.AppLog
import com.lhstack.suxi.dns.dns.DnsServerStore
import com.lhstack.suxi.dns.dns.DnsVpnService
import com.lhstack.suxi.dns.dns.VpnState
import com.lhstack.suxi.dns.dns.VpnStatus
import com.lhstack.suxi.dns.model.DnsProtocol
import com.lhstack.suxi.dns.model.DnsServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class AppScreen {
    HOME,
    DNS_SERVERS,
}

class DnsConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DnsServerStore(application)

    private val _servers = MutableStateFlow(store.load())
    val servers: StateFlow<List<DnsServerConfig>> = _servers.asStateFlow()
    val vpnStatus: StateFlow<VpnStatus> = DnsVpnService.status
    val logs = AppLog.entries

    private val _currentScreen = MutableStateFlow(AppScreen.HOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    fun openHome() {
        _currentScreen.value = AppScreen.HOME
    }

    fun openDnsServers() {
        _currentScreen.value = AppScreen.DNS_SERVERS
    }

    fun addServer() {
        val id = (_servers.value.maxOfOrNull(DnsServerConfig::id) ?: 0) + 1
        replaceServers(
            _servers.value + DnsServerConfig(
                id = id,
                protocol = DnsProtocol.UDP,
                host = "",
                port = DnsProtocol.UDP.defaultPort,
            ),
        )
    }

    fun updateServer(config: DnsServerConfig) {
        replaceServers(
            _servers.value.map { current ->
                if (current.id == config.id) config else current
            },
        )
    }

    fun removeServer(id: Long) {
        replaceServers(_servers.value.filterNot { it.id == id })
    }

    fun toggleServer(id: Long) {
        replaceServers(
            _servers.value.map { current ->
                if (current.id == id) current.copy(enabled = !current.enabled) else current
            },
        )
    }

    fun createVpnPermissionIntent(): Intent? {
        validateServers()
        return VpnService.prepare(getApplication())
    }

    fun startVpn() {
        val context = getApplication<Application>()
        // 从内存中的完整配置列表启动；服务侧再过滤 enabled。
        val configs = validateServersForStart()
        val encodedConfigs = Json.encodeToString(
            ListSerializer(DnsServerConfig.serializer()),
            configs,
        )
        val intent = Intent(context, DnsVpnService::class.java)
            .setAction(DnsVpnService.ACTION_START)
            .putExtra(DnsVpnService.EXTRA_CONFIGS, encodedConfigs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopVpn() {
        val context = getApplication<Application>()
        context.startService(
            Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP),
        )
    }

    fun clearLogs() {
        AppLog.clear()
    }

    fun isVpnActive(status: VpnStatus): Boolean {
        return status.state == VpnState.RUNNING || status.state == VpnState.STARTING
    }

    private fun replaceServers(servers: List<DnsServerConfig>) {
        _servers.value = servers
        store.save(servers)
    }

    private fun validateServers() {
        validateServersForStart()
    }

    /** 返回完整列表（含未启用），服务侧只使用 enabled。 */
    private fun validateServersForStart(): List<DnsServerConfig> {
        val all = _servers.value
        val enabledServers = all.filter(DnsServerConfig::enabled)
        require(enabledServers.isNotEmpty()) { "请至少启用一个 DNS 服务器" }
        enabledServers.forEach(DnsServerConfig::validate)
        return all
    }
}
