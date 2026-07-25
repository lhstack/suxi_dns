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
import com.lhstack.suxi.dns.model.DnsServerGroup
import com.lhstack.suxi.dns.model.DomainBlockRule
import com.lhstack.suxi.dns.model.LocalDnsRecord
import com.lhstack.suxi.dns.model.LocalRecordType
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class DnsVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var resolver: CompositeResolver? = null
    private var serviceScope: CoroutineScope? = null
    private val outputMutex = Mutex()
    private val stopping = AtomicBoolean(false)
    private var blockMatcher: DomainBlockMatcher = DomainBlockMatcher(emptyList())
    private var localRecords: List<LocalDnsRecord> = emptyList()
    private val localNameMatcher = LocalNameMatcher()
    private val responseCache = DnsResponseCache()

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
            val groups = Json.decodeFromString(
                ListSerializer(DnsServerGroup.serializer()),
                encodedConfigs,
            )
            groups.filter(DnsServerGroup::enabled).forEach(DnsServerGroup::validate)
            require(groups.any { it.enabled && it.enabledServers.isNotEmpty() }) {
                "至少需要启用一个含有效上游的 DNS 分组"
            }
            val local = decodeListExtra(
                intent,
                EXTRA_LOCAL_RECORDS,
                LocalDnsRecord.serializer(),
            )
            val blocks = decodeListExtra(
                intent,
                EXTRA_BLOCK_RULES,
                DomainBlockRule.serializer(),
            )
            startVpn(groups, local, blocks)
        } catch (exception: Exception) {
            val message = exception.message ?: "无法启动 DNS VPN"
            AppLog.error(message)
            publishStatus(VpnState.ERROR, message)
            requestStop("启动失败")
        }
    }

    private fun <T> decodeListExtra(
        intent: Intent,
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> {
        val encoded = intent.getStringExtra(key) ?: return emptyList()
        return Json.decodeFromString(ListSerializer(serializer), encoded)
    }

    private fun startVpn(
        groups: List<DnsServerGroup>,
        local: List<LocalDnsRecord>,
        blocks: List<DomainBlockRule>,
    ) {
        closeResources()
        stopping.set(false)
        AppLog.clear()
        responseCache.clear()
        val enabledGroups = groups.filter { it.enabled && it.enabledServers.isNotEmpty() }
        AppLog.vpn("正在启动 DNS VPN，共 ${enabledGroups.size} 个分组")
        enabledGroups.forEachIndexed { index, group ->
            val upstreams = group.enabledServers.joinToString(", ") { it.displayAddress }
            AppLog.info("分组 ${index + 1}「${group.displayName}」: $upstreams")
        }
        localRecords = local.filter(LocalDnsRecord::enabled)
        blockMatcher = DomainBlockMatcher(blocks)
        AppLog.info(
            "本地规则: ${localRecords.size} 条自定义解析, " +
                "${blocks.count(DomainBlockRule::enabled)} 条拦截",
        )
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

            val establishedResolver = CompositeResolver(this, this, enabledGroups)
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
        val querySemaphore = Semaphore(MAX_IN_FLIGHT_QUERIES)
        try {
            while (currentCoroutineContext().isActive && !stopping.get()) {
                val length = try {
                    withContext(Dispatchers.IO) {
                        input.read(readBuffer)
                    }
                } catch (exception: Exception) {
                    if (currentCoroutineContext().isActive && !stopping.get()) {
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
                        writePacketSafely(output, dnsPacket.createServFailResponse())
                        return@launch
                    }
                    try {
                        handleDnsQuery(dnsPacket, output)
                    } catch (exception: Exception) {
                        if (!stopping.get()) {
                            AppLog.error(
                                "处理查询失败: ${exception.message ?: exception::class.simpleName}",
                            )
                        }
                    } finally {
                        querySemaphore.release()
                    }
                }
            }
        } finally {
            if (currentCoroutineContext().isActive && !stopping.get()) {
                AppLog.vpn("读包循环结束，VPN 服务仍保持前台状态")
            }
        }
    }

    private suspend fun handleDnsQuery(
        dnsPacket: Ipv4UdpDnsPacket,
        output: FileOutputStream,
    ) {
        if (stopping.get()) return
        val query = dnsPacket.dnsMessage
        val question = parseDnsQuestion(query)
        val queryName = question?.name ?: extractDnsQueryName(query)
        AppLog.query("查询 $queryName")
        val startedAt = System.currentTimeMillis()

        // 1) 拦截规则：直接 NXDOMAIN
        val blocked = blockMatcher.findMatch(queryName)
        if (blocked != null) {
            val response = buildDnsResponse(query, rcode = RCODE_NXDOMAIN)
            AppLog.error(
                "$queryName 已拦截 | 规则 ${blocked.displaySummary} | " +
                    "${System.currentTimeMillis() - startedAt}ms",
            )
            writePacketSafely(output, dnsPacket.createResponse(response))
            return
        }

        // 2) 本地自定义解析（A / AAAA / CNAME；对 A/AAAA 会跟随本地 CNAME）
        if (question != null) {
            val localResponse = resolveLocal(query, queryName, question.type)
            if (localResponse != null) {
                val elapsedMs = System.currentTimeMillis() - startedAt
                val answers = summarizeDnsAnswers(localResponse)
                AppLog.success(
                    "$queryName → $answers | 本地规则 | ${elapsedMs}ms",
                )
                writePacketSafely(output, dnsPacket.createResponse(localResponse))
                return
            }
        }

        // 3) 缓存命中：直接返回，不走上游（改写事务 ID 以匹配当前查询）
        if (question != null) {
            val cached = responseCache.get(queryName, question.type)
            if (cached != null) {
                val response = rewriteDnsResponseId(cached, query)
                val elapsedMs = System.currentTimeMillis() - startedAt
                val answers = summarizeDnsAnswers(response)
                AppLog.success("$queryName → $answers | 缓存 | ${elapsedMs}ms")
                writePacketSafely(output, dnsPacket.createResponse(response))
                return
            }
        }

        // 4) 上游竞速
        val resolution = try {
            requireNotNull(resolver) { "DNS 解析器不可用" }
                .resolve(query)
        } catch (exception: Exception) {
            if (stopping.get()) return
            val message = exception.message ?: "所有 DNS 上游均失败"
            AppLog.error("$queryName: $message")
            writePacketSafely(output, dnsPacket.createServFailResponse())
            return
        }
        if (stopping.get()) return
        // 写入缓存：仅对可解析出问题段的成功/NXDOMAIN 响应缓存
        if (question != null) {
            responseCache.put(queryName, question.type, resolution.response)
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        val answers = summarizeDnsAnswers(resolution.response)
        AppLog.success(
            "$queryName → $answers | 上游 ${resolution.upstream} | ${elapsedMs}ms | ${resolution.response.size}字节",
        )
        writePacketSafely(output, dnsPacket.createResponse(resolution.response))
    }

    /**
     * 本地解析。
     * - A/AAAA/CNAME 类型直接命中对应记录则立即返回，不走上游。
     * - 查询 A/AAAA 但只命中 CNAME：返回 CNAME，并尽量解析目标的 A/AAAA
     *   （先本地，再仅对 CNAME 目标请求上游一次），减少客户端二次查询。
     */
    private suspend fun resolveLocal(
        query: ByteArray,
        queryName: String,
        qtype: Int,
    ): ByteArray? {
        val hit = findLocalRecord(queryName, qtype) ?: return null
        if (hit.type != LocalRecordType.CNAME || (qtype != TYPE_A && qtype != TYPE_AAAA)) {
            return buildDnsResponse(query, answers = localRecordToAnswers(hit, queryName))
        }

        val answers = localRecordToAnswers(hit, queryName).toMutableList()
        val target = normalizeDnsName(hit.value)
        // 1) 目标是否也有本地 A/AAAA
        val localTarget = findLocalRecordExact(target, qtype)
        if (localTarget != null) {
            answers += localRecordToAnswers(localTarget, target)
            return buildDnsResponse(query, answers = answers)
        }
        // 2) 仅对 CNAME 目标向上游要一次 A/AAAA（不是对原域名再查）
        val activeResolver = resolver ?: return buildDnsResponse(query, answers = answers)
        return try {
            val followQuery = buildDnsQuery(target, qtype, query[0], query[1])
            val upstream = activeResolver.resolve(followQuery)
            val followed = extractAnswerRecords(upstream.response)
            if (followed.isNotEmpty()) {
                answers += followed
            }
            buildDnsResponse(query, answers = answers)
        } catch (_: Exception) {
            // 跟随失败仍返回 CNAME，客户端可自行再查目标
            buildDnsResponse(query, answers = answers)
        }
    }

    private fun findLocalRecord(queryName: String, qtype: Int): LocalDnsRecord? {
        val candidates = localRecords.filter { record ->
            localNameMatcher.matches(record.name, queryName)
        }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.type.wireType == qtype }?.let { return it }
        if (qtype == TYPE_A || qtype == TYPE_AAAA) {
            return candidates.firstOrNull { it.type == LocalRecordType.CNAME }
        }
        return null
    }

    private fun findLocalRecordExact(queryName: String, qtype: Int): LocalDnsRecord? {
        return localRecords.firstOrNull { record ->
            localNameMatcher.matches(record.name, queryName) && record.type.wireType == qtype
        }
    }

    private suspend fun writePacketSafely(output: FileOutputStream, packet: ByteArray) {
        if (stopping.get()) return
        try {
            outputMutex.withLock {
                if (stopping.get()) return
                output.write(packet)
            }
        } catch (exception: Exception) {
            if (!stopping.get()) {
                AppLog.error("写入 VPN 报文失败: ${exception.message}")
            }
        }
    }

    private fun requestStop(reason: String = "手动停止") {
        if (!stopping.compareAndSet(false, true) && _status.value.state == VpnState.STOPPED) {
            return
        }
        AppLog.vpn("正在停止 DNS VPN：$reason")
        closeResources()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // 通知可能已移除
        }
        stopSelf()
        if (_status.value.state != VpnState.ERROR) {
            AppLog.vpn("DNS VPN 已停止（$reason）")
            publishStatus(VpnState.STOPPED, "DNS VPN 已停止")
        }
    }

    private fun closeResources() {
        serviceScope?.cancel()
        serviceScope = null
        try {
            resolver?.close()
        } catch (_: Exception) {
        }
        resolver = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
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
        const val EXTRA_LOCAL_RECORDS = "local_records"
        const val EXTRA_BLOCK_RULES = "block_rules"

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
