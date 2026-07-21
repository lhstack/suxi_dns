package com.lhstack.suxi.dns.dns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.lhstack.suxi.dns.MainActivity
import com.lhstack.suxi.dns.dns.resolver.CompositeResolver
import com.lhstack.suxi.dns.model.DnsServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress

class DnsVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var resolver: CompositeResolver? = null
    private var serviceScope: CoroutineScope? = null
    private val outputMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFromIntent(intent)
            ACTION_STOP -> requestStop("收到停止指令")
            null -> {
                AppLog.error("VPN 服务重启时缺少 DNS 配置")
                publishStatus(VpnState.ERROR, "VPN 服务重启时缺少 DNS 配置")
                requestStop("缺少配置")
            }
        }
        // 配置在 Intent 里，被杀后无法无配置恢复，故不使用 START_STICKY。
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        AppLog.vpn("VPN 权限已被系统撤销")
        publishStatus(VpnState.STOPPED, "VPN 权限已被系统撤销")
        requestStop("系统撤销 VPN 权限")
    }

    override fun onDestroy() {
        closeResources()
        super.onDestroy()
    }

    private fun startFromIntent(intent: Intent) {
        try {
            val encodedConfigs = requireNotNull(intent.getStringExtra(EXTRA_CONFIGS)) {
                "缺少 DNS 服务器配置"
            }
            val configs = Json.decodeFromString(
                ListSerializer(DnsServerConfig.serializer()),
                encodedConfigs,
            )
            configs.filter(DnsServerConfig::enabled).forEach(DnsServerConfig::validate)
            require(configs.any(DnsServerConfig::enabled)) {
                "至少需要启用一个 DNS 服务器"
            }
            startVpn(configs)
        } catch (exception: Exception) {
            val message = exception.message ?: "无法启动 DNS VPN"
            AppLog.error(message)
            publishStatus(VpnState.ERROR, message)
            requestStop("启动失败")
        }
    }

    private fun startVpn(configs: List<DnsServerConfig>) {
        closeResources()
        AppLog.clear()
        val enabled = configs.filter(DnsServerConfig::enabled)
        AppLog.vpn("正在启动 DNS VPN，共 ${enabled.size} 个上游")
        enabled.forEach { config ->
            AppLog.info("上游: ${config.displayAddress}")
        }
        publishStatus(VpnState.STARTING, "正在启动 DNS VPN")
        startForeground(NOTIFICATION_ID, createNotification())

        try {
            val establishedInterface = Builder()
                .setSession("速析 DNS")
                .setMtu(MTU)
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS)
                .addRoute(DNS_ADDRESS, 32)
                .establish()
                ?: error("系统无法建立 VPN 接口")

            val establishedResolver = CompositeResolver(this, configs)
            vpnInterface = establishedInterface
            resolver = establishedResolver
            serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
                scope.launch { processPackets(establishedInterface, scope) }
            }
            AppLog.vpn("DNS VPN 已运行（虚拟 DNS $DNS_ADDRESS）")
            publishStatus(VpnState.RUNNING, "DNS VPN 运行中")
        } catch (exception: Exception) {
            closeResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            throw exception
        }
    }

    private suspend fun processPackets(
        tunnel: ParcelFileDescriptor,
        scope: CoroutineScope,
    ) {
        val dnsAddress = InetAddress.getByName(DNS_ADDRESS).address
        val input = FileInputStream(tunnel.fileDescriptor)
        val output = FileOutputStream(tunnel.fileDescriptor)
        val readBuffer = ByteArray(MAX_PACKET_SIZE)
        // 限制并发解析，避免失败重试风暴拖垮前台服务。
        val querySemaphore = kotlinx.coroutines.sync.Semaphore(MAX_IN_FLIGHT_QUERIES)
        try {
            while (currentCoroutineContext().isActive) {
                val length = try {
                    withContext(Dispatchers.IO) {
                        input.read(readBuffer)
                    }
                } catch (exception: Exception) {
                    if (currentCoroutineContext().isActive) {
                        AppLog.error("读取 VPN 报文失败: ${exception.message}")
                    }
                    break
                }
                if (length < 0) {
                    AppLog.vpn("VPN 接口已关闭，停止读包循环")
                    break
                }
                if (length == 0) continue
                val packetBytes = readBuffer.copyOf(length)
                val dnsPacket = Ipv4UdpDnsPacket.parse(packetBytes, dnsAddress) ?: continue
                scope.launch {
                    if (!querySemaphore.tryAcquire()) {
                        AppLog.error("并发查询过多，丢弃请求 ${extractDnsQueryName(dnsPacket.dnsMessage)}")
                        writePacket(output, dnsPacket.createServFailResponse())
                        return@launch
                    }
                    try {
                        handleDnsQuery(dnsPacket, output)
                    } finally {
                        querySemaphore.release()
                    }
                }
            }
        } finally {
            // 读包循环结束不自动 stopSelf：可能是短暂 I/O 异常；由用户/系统显式停止。
            if (currentCoroutineContext().isActive) {
                AppLog.vpn("读包循环结束，VPN 服务仍保持前台状态")
            }
        }
    }

    private suspend fun handleDnsQuery(
        dnsPacket: Ipv4UdpDnsPacket,
        output: FileOutputStream,
    ) {
        val queryName = extractDnsQueryName(dnsPacket.dnsMessage)
        AppLog.query("查询 $queryName")
        val startedAt = System.currentTimeMillis()
        val resolution = try {
            requireNotNull(resolver) { "DNS 解析器不可用" }
                .resolve(dnsPacket.dnsMessage)
        } catch (exception: Exception) {
            val message = exception.message ?: "所有 DNS 上游均失败"
            AppLog.error("$queryName: $message")
            // 不要用长错误刷状态栏/通知，保持 RUNNING 短文案。
            writePacket(output, dnsPacket.createServFailResponse())
            return
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        val answers = summarizeDnsAnswers(resolution.response)
        AppLog.success(
            "$queryName → $answers | 上游 ${resolution.upstream} | ${elapsedMs}ms | ${resolution.response.size}字节",
        )
        writePacket(output, dnsPacket.createResponse(resolution.response))
    }

    private suspend fun writePacket(output: FileOutputStream, packet: ByteArray) {
        outputMutex.withLock {
            output.write(packet)
        }
    }

    private fun requestStop(reason: String = "手动停止") {
        AppLog.vpn("正在停止 DNS VPN：$reason")
        closeResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (_status.value.state != VpnState.ERROR) {
            AppLog.vpn("DNS VPN 已停止（$reason）")
            publishStatus(VpnState.STOPPED, "DNS VPN 已停止")
        }
    }

    private fun closeResources() {
        serviceScope?.cancel()
        serviceScope = null
        resolver?.close()
        resolver = null
        vpnInterface?.close()
        vpnInterface = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "速析 DNS",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("速析 DNS")
            .setContentText("自定义 DNS 解析已启用")
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun publishStatus(state: VpnState, message: String) {
        _status.value = VpnStatus(state, message)
    }

    companion object {
        const val ACTION_START = "com.lhstack.suxi.dns.action.START_VPN"
        const val ACTION_STOP = "com.lhstack.suxi.dns.action.STOP_VPN"
        const val EXTRA_CONFIGS = "dns_servers"

        private const val VPN_ADDRESS = "10.10.10.1"
        private const val DNS_ADDRESS = "10.10.10.2"
        private const val MTU = 1500
        private const val MAX_PACKET_SIZE = 65_535
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "dns_vpn"
        private const val MAX_IN_FLIGHT_QUERIES = 32

        private val _status = MutableStateFlow(VpnStatus(VpnState.STOPPED, "DNS VPN 已停止"))
        val status: StateFlow<VpnStatus> = _status.asStateFlow()
    }
}

data class VpnStatus(
    val state: VpnState,
    val message: String,
)

enum class VpnState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}
