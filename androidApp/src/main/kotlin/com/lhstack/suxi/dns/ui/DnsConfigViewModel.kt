package com.lhstack.suxi.dns.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lhstack.suxi.dns.dns.AppLog
import com.lhstack.suxi.dns.dns.BlockRulesInitializer
import com.lhstack.suxi.dns.dns.DnsServerStore
import com.lhstack.suxi.dns.dns.DnsVpnService
import com.lhstack.suxi.dns.dns.JsonListStore
import com.lhstack.suxi.dns.dns.ManualDnsResolver
import com.lhstack.suxi.dns.dns.ManualQueryResult
import com.lhstack.suxi.dns.dns.ManualQueryType
import com.lhstack.suxi.dns.dns.findManualTypeConflict
import com.lhstack.suxi.dns.dns.VpnState
import com.lhstack.suxi.dns.dns.VpnStatus
import com.lhstack.suxi.dns.model.BlockMatchMode
import com.lhstack.suxi.dns.model.DnsProtocol
import com.lhstack.suxi.dns.model.DnsServerConfig
import com.lhstack.suxi.dns.model.DnsServerGroup
import com.lhstack.suxi.dns.model.DomainBlockRule
import com.lhstack.suxi.dns.model.LocalDnsRecord
import com.lhstack.suxi.dns.model.LocalRecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

enum class AppScreen {
    HOME,
    DNS_SERVERS,
    LOCAL_RECORDS,
    BLOCK_RULES,
    MANUAL_LOOKUP,
}

/**
 * 手动解析页状态。
 * [running] 期间禁用再次查询；[results] 为空且无错误表示尚未查询。
 */
data class ManualLookupState(
    val input: String = "",
    val selectedTypes: Set<ManualQueryType> = setOf(ManualQueryType.A),
    val selectedGroupId: Long? = null,
    val running: Boolean = false,
    val results: List<ManualQueryResult> = emptyList(),
    val error: String? = null,
)

class DnsConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val serverStore = DnsServerStore(application)
    private val localStore = JsonListStore(
        application,
        "local_dns_records.json",
        LocalDnsRecord.serializer(),
    )
    private val blockStore = JsonListStore(
        application,
        BlockRulesInitializer.LOCAL_FILE_NAME,
        DomainBlockRule.serializer(),
    )

    private val _groups = MutableStateFlow(serverStore.load())
    val groups: StateFlow<List<DnsServerGroup>> = _groups.asStateFlow()

    private val _localRecords = MutableStateFlow(localStore.load())
    val localRecords: StateFlow<List<LocalDnsRecord>> = _localRecords.asStateFlow()

    // Application.onCreate 已做过「无文件则灌内置规则」；此处只读本地文件
    private val _blockRules = MutableStateFlow(blockStore.load())
    val blockRules: StateFlow<List<DomainBlockRule>> = _blockRules.asStateFlow()

    val vpnStatus: StateFlow<VpnStatus> = DnsVpnService.status
    val logs = AppLog.entries

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    private val _currentScreen = MutableStateFlow(AppScreen.HOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val manualResolver = ManualDnsResolver(application)
    private val _manualLookup = MutableStateFlow(ManualLookupState())
    val manualLookup: StateFlow<ManualLookupState> = _manualLookup.asStateFlow()

    fun openHome() {
        _currentScreen.value = AppScreen.HOME
    }

    fun openDnsServers() {
        _currentScreen.value = AppScreen.DNS_SERVERS
    }

    fun openLocalRecords() {
        _currentScreen.value = AppScreen.LOCAL_RECORDS
    }

    fun openBlockRules() {
        _currentScreen.value = AppScreen.BLOCK_RULES
    }

    fun openManualLookup() {
        _currentScreen.value = AppScreen.MANUAL_LOOKUP
    }

    fun updateManualInput(input: String) {
        _manualLookup.value = _manualLookup.value.copy(input = input)
    }

    /** 切换某个解析类型的选中状态；切换后清除旧的类型冲突提示。 */
    fun toggleManualType(type: ManualQueryType) {
        val current = _manualLookup.value
        val updated = current.selectedTypes.toMutableSet().apply {
            if (!add(type)) remove(type)
        }
        _manualLookup.value = current.copy(
            selectedTypes = updated,
            error = findManualTypeConflict(updated),
        )
    }

    fun selectManualGroup(groupId: Long) {
        _manualLookup.value = _manualLookup.value.copy(selectedGroupId = groupId)
    }

    /**
     * 执行手动解析：用选中的分组、选中的类型解析输入的域名或 IP。
     * 输入、类型冲突、分组缺失等在此显式校验并回填错误，不静默吞掉。
     */
    fun runManualLookup() {
        val state = _manualLookup.value
        if (state.running) return
        val input = state.input.trim()
        if (input.isEmpty()) {
            _manualLookup.value = state.copy(error = "请输入域名或 IP 地址")
            return
        }
        val types = ManualQueryType.entries.filter { it in state.selectedTypes }
        if (types.isEmpty()) {
            _manualLookup.value = state.copy(error = "请至少选择一种解析类型")
            return
        }
        findManualTypeConflict(types.toSet())?.let {
            _manualLookup.value = state.copy(error = it)
            return
        }
        val group = resolveManualGroup(state.selectedGroupId)
        if (group == null) {
            _manualLookup.value = state.copy(error = "请选择一个含有效上游的 DNS 分组")
            return
        }
        _manualLookup.value = state.copy(running = true, error = null, results = emptyList())
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    manualResolver.resolve(input, types, group)
                }
                _manualLookup.value = _manualLookup.value.copy(running = false, results = results)
            } catch (exception: Exception) {
                _manualLookup.value = _manualLookup.value.copy(
                    running = false,
                    error = exception.message ?: "解析失败",
                )
            }
        }
    }

    /** 选中的分组必须启用且含有效上游；未指定时取第一个可用分组。 */
    private fun resolveManualGroup(groupId: Long?): DnsServerGroup? {
        val usable = _groups.value.filter { it.enabled && it.enabledServers.isNotEmpty() }
        if (usable.isEmpty()) return null
        return groupId?.let { id -> usable.firstOrNull { it.id == id } } ?: usable.first()
    }

    fun addGroup() {
        val id = nextId(_groups.value.map(DnsServerGroup::id))
        replaceGroups(
            _groups.value + DnsServerGroup(
                id = id,
                name = "",
                servers = emptyList(),
            ),
        )
    }

    fun updateGroupName(groupId: Long, name: String) {
        replaceGroups(
            _groups.value.map { if (it.id == groupId) it.copy(name = name) else it },
        )
    }

    fun removeGroup(groupId: Long) {
        replaceGroups(_groups.value.filterNot { it.id == groupId })
    }

    fun toggleGroup(groupId: Long) {
        replaceGroups(
            _groups.value.map {
                if (it.id == groupId) it.copy(enabled = !it.enabled) else it
            },
        )
    }

    /** 拖拽重排分组：把 [fromIndex] 处的分组移动到 [toIndex]。 */
    fun moveGroup(fromIndex: Int, toIndex: Int) {
        val current = _groups.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        if (fromIndex == toIndex) return
        val reordered = current.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        replaceGroups(reordered)
    }

    fun addServer(groupId: Long) {
        val allIds = _groups.value.flatMap { group -> group.servers.map(DnsServerConfig::id) }
        val id = nextId(allIds)
        replaceGroups(
            _groups.value.map { group ->
                if (group.id != groupId) return@map group
                group.copy(
                    servers = group.servers + DnsServerConfig(
                        id = id,
                        protocol = DnsProtocol.UDP,
                        host = "",
                        port = DnsProtocol.UDP.defaultPort,
                    ),
                )
            },
        )
    }

    fun updateServer(groupId: Long, config: DnsServerConfig) {
        replaceGroups(
            _groups.value.map { group ->
                if (group.id != groupId) return@map group
                group.copy(
                    servers = group.servers.map { if (it.id == config.id) config else it },
                )
            },
        )
    }

    fun removeServer(groupId: Long, serverId: Long) {
        replaceGroups(
            _groups.value.map { group ->
                if (group.id != groupId) return@map group
                group.copy(servers = group.servers.filterNot { it.id == serverId })
            },
        )
    }

    fun toggleServer(groupId: Long, serverId: Long) {
        replaceGroups(
            _groups.value.map { group ->
                if (group.id != groupId) return@map group
                group.copy(
                    servers = group.servers.map {
                        if (it.id == serverId) it.copy(enabled = !it.enabled) else it
                    },
                )
            },
        )
    }

    fun addLocalRecord() {
        val id = nextId(_localRecords.value.map(LocalDnsRecord::id))
        replaceLocalRecords(
            _localRecords.value + LocalDnsRecord(
                id = id,
                name = "",
                type = LocalRecordType.A,
                value = "",
                ttlSeconds = 300,
            ),
        )
    }

    fun updateLocalRecord(record: LocalDnsRecord) {
        replaceLocalRecords(_localRecords.value.map { if (it.id == record.id) record else it })
    }

    fun removeLocalRecord(id: Long) {
        replaceLocalRecords(_localRecords.value.filterNot { it.id == id })
    }

    fun toggleLocalRecord(id: Long) {
        replaceLocalRecords(
            _localRecords.value.map {
                if (it.id == id) it.copy(enabled = !it.enabled) else it
            },
        )
    }

    fun addBlockRule() {
        val id = nextId(_blockRules.value.map(DomainBlockRule::id))
        replaceBlockRules(
            _blockRules.value + DomainBlockRule(
                id = id,
                pattern = "",
                matchMode = BlockMatchMode.WILDCARD,
            ),
        )
    }

    fun updateBlockRule(rule: DomainBlockRule) {
        replaceBlockRules(_blockRules.value.map { if (it.id == rule.id) rule else it })
    }

    fun removeBlockRule(id: Long) {
        replaceBlockRules(_blockRules.value.filterNot { it.id == id })
    }

    fun toggleBlockRule(id: Long) {
        replaceBlockRules(
            _blockRules.value.map {
                if (it.id == id) it.copy(enabled = !it.enabled) else it
            },
        )
    }

    fun clearAllBlockRules() {
        if (_blockRules.value.isEmpty()) {
            _importStatus.value = "当前没有可清空的拦截规则"
            return
        }
        val count = _blockRules.value.size
        replaceBlockRules(emptyList())
        _importStatus.value = "已清空 $count 条拦截规则"
    }

    fun clearImportStatus() {
        _importStatus.value = null
    }

    /**
     * 从 JSON 批量导入拦截规则。
     * 格式与应用内 `domain_block_rules.json` 相同。
     * 规则键为 matchMode + pattern（忽略大小写）：
     * - 已存在 → 覆盖（保留原 id，更新 pattern/matchMode/enabled/note）
     * - 不存在 → 追加（分配新 id）
     */
    fun importBlockRulesFromText(text: String): Int {
        val parsed = try {
            parseBlockRulesJson(text)
        } catch (exception: Exception) {
            _importStatus.value = "JSON 解析失败: ${exception.message}"
            return 0
        }
        if (parsed.isEmpty()) {
            _importStatus.value = "JSON 中没有有效规则"
            return 0
        }
        val valid = ArrayList<DomainBlockRule>()
        var invalid = 0
        for (item in parsed) {
            try {
                item.validate()
                valid += item
            } catch (_: Exception) {
                invalid++
            }
        }
        if (valid.isEmpty()) {
            _importStatus.value = "没有通过校验的规则" +
                if (invalid > 0) "（$invalid 条无效）" else ""
            return 0
        }

        val byKey = LinkedHashMap<String, DomainBlockRule>()
        for (rule in _blockRules.value) {
            byKey[blockRuleKey(rule)] = rule
        }
        var next = nextId(_blockRules.value.map(DomainBlockRule::id))
        var overwritten = 0
        var appended = 0
        for (item in valid) {
            val key = blockRuleKey(item)
            val existing = byKey[key]
            if (existing != null) {
                byKey[key] = existing.copy(
                    pattern = item.pattern,
                    matchMode = item.matchMode,
                    enabled = item.enabled,
                    note = item.note,
                )
                overwritten++
            } else {
                byKey[key] = item.copy(id = next++)
                appended++
            }
        }
        replaceBlockRules(byKey.values.toList())
        _importStatus.value = buildString {
            append("导入完成：覆盖 $overwritten 条，追加 $appended 条")
            if (invalid > 0) append("，跳过 $invalid 条无效")
        }
        return overwritten + appended
    }

    fun importBlockRulesFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取文件")
                }
                importBlockRulesFromText(text)
            } catch (exception: Exception) {
                _importStatus.value = "从文件导入失败: ${exception.message}"
            }
        }
    }

    fun importBlockRulesFromUrl(urlText: String) {
        val trimmed = urlText.trim()
        if (trimmed.isEmpty()) {
            _importStatus.value = "URL 不能为空"
            return
        }
        viewModelScope.launch {
            try {
                _importStatus.value = "正在从 URL 下载…"
                val text = withContext(Dispatchers.IO) {
                    downloadText(trimmed)
                }
                importBlockRulesFromText(text)
            } catch (exception: Exception) {
                _importStatus.value = "从 URL 导入失败: ${exception.message}"
            }
        }
    }

    /**
     * 从远程默认源更新拦截规则（覆盖同名、追加新项）。
     * 与「首次启动内置初始化」无关；网络失败直接报错，不静默用 assets。
     */
    fun updateDefaultBlockRules() {
        viewModelScope.launch {
            try {
                _importStatus.value = "正在更新默认拦截规则…"
                val text = withContext(Dispatchers.IO) {
                    downloadText(DEFAULT_BLOCK_RULES_URL)
                }
                importBlockRulesFromText(text)
            } catch (exception: Exception) {
                _importStatus.value = "更新默认规则失败: ${exception.message}"
            }
        }
    }

    private fun blockRuleKey(rule: DomainBlockRule): String =
        "${rule.matchMode}|${rule.pattern.lowercase()}"

    companion object {
        const val DEFAULT_BLOCK_RULES_URL =
            "https://raw.githubusercontent.com/lhstack/suxi_dns/refs/heads/main/block_rules.json"
    }

    fun createVpnPermissionIntent(): Intent? {
        validateForStart()
        return VpnService.prepare(getApplication())
    }

    fun startVpn() {
        val context = getApplication<Application>()
        val groups = validateServersForStart()
        val locals = _localRecords.value.filter(LocalDnsRecord::enabled).onEach(LocalDnsRecord::validate)
        val blocks = _blockRules.value.filter(DomainBlockRule::enabled).onEach(DomainBlockRule::validate)
        val intent = Intent(context, DnsVpnService::class.java)
            .setAction(DnsVpnService.ACTION_START)
            .putExtra(
                DnsVpnService.EXTRA_CONFIGS,
                Json.encodeToString(ListSerializer(DnsServerGroup.serializer()), groups),
            )
            .putExtra(
                DnsVpnService.EXTRA_LOCAL_RECORDS,
                Json.encodeToString(ListSerializer(LocalDnsRecord.serializer()), locals),
            )
            .putExtra(
                DnsVpnService.EXTRA_BLOCK_RULES,
                Json.encodeToString(ListSerializer(DomainBlockRule.serializer()), blocks),
            )
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

    private fun replaceGroups(groups: List<DnsServerGroup>) {
        _groups.value = groups
        serverStore.save(groups)
    }

    private fun replaceLocalRecords(records: List<LocalDnsRecord>) {
        _localRecords.value = records
        localStore.save(records)
    }

    private fun replaceBlockRules(rules: List<DomainBlockRule>) {
        _blockRules.value = rules
        blockStore.save(rules)
    }

    private fun validateForStart() {
        validateServersForStart()
        _localRecords.value.filter(LocalDnsRecord::enabled).forEach(LocalDnsRecord::validate)
        _blockRules.value.filter(DomainBlockRule::enabled).forEach(DomainBlockRule::validate)
    }

    private fun validateServersForStart(): List<DnsServerGroup> {
        val all = _groups.value
        val activeGroups = all.filter { it.enabled && it.enabledServers.isNotEmpty() }
        require(activeGroups.isNotEmpty()) { "请至少启用一个含有效上游的 DNS 分组" }
        all.filter(DnsServerGroup::enabled).forEach(DnsServerGroup::validate)
        return all
    }

    private fun nextId(ids: List<Long>): Long = (ids.maxOrNull() ?: 0L) + 1L

    private fun downloadText(urlText: String): String {
        val url = URL(urlText)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "suxi-dns/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("HTTP $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private val importJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun parseBlockRulesJson(text: String): List<DomainBlockRule> {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "内容为空" }
        return importJson.decodeFromString(
            ListSerializer(DomainBlockRule.serializer()),
            trimmed,
        )
    }
}
