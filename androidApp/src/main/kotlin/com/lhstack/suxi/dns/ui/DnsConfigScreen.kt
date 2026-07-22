package com.lhstack.suxi.dns.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lhstack.suxi.dns.dns.AppLogEntry
import com.lhstack.suxi.dns.dns.LogLevel
import com.lhstack.suxi.dns.dns.VpnState
import com.lhstack.suxi.dns.model.BlockMatchMode
import com.lhstack.suxi.dns.model.DnsProtocol
import com.lhstack.suxi.dns.model.DnsServerConfig
import com.lhstack.suxi.dns.model.DomainBlockRule
import com.lhstack.suxi.dns.model.LocalDnsRecord
import com.lhstack.suxi.dns.model.LocalRecordType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsConfigScreen(
    viewModel: DnsConfigViewModel,
    onStartRequested: () -> Unit,
) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val localRecords by viewModel.localRecords.collectAsState()
    val blockRules by viewModel.blockRules.collectAsState()
    val vpnStatus by viewModel.vpnStatus.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val importStatus by viewModel.importStatus.collectAsState()
    val isRunning = viewModel.isVpnActive(vpnStatus)
    var localError by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentScreen) {
                            AppScreen.HOME -> "速析 DNS"
                            AppScreen.DNS_SERVERS -> "DNS 服务器"
                            AppScreen.LOCAL_RECORDS -> "自定义解析"
                            AppScreen.BLOCK_RULES -> "域名拦截"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Text("≡", style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("首页") },
                            onClick = {
                                menuExpanded = false
                                viewModel.openHome()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("DNS 服务器") },
                            onClick = {
                                menuExpanded = false
                                viewModel.openDnsServers()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("自定义解析") },
                            onClick = {
                                menuExpanded = false
                                viewModel.openLocalRecords()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("域名拦截") },
                            onClick = {
                                menuExpanded = false
                                viewModel.openBlockRules()
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
        floatingActionButton = {
            if (!isRunning) {
                when (currentScreen) {
                    AppScreen.DNS_SERVERS -> FloatingActionButton(onClick = viewModel::addServer) {
                        Text("+")
                    }
                    AppScreen.LOCAL_RECORDS -> FloatingActionButton(onClick = viewModel::addLocalRecord) {
                        Text("+")
                    }
                    AppScreen.BLOCK_RULES -> FloatingActionButton(onClick = viewModel::addBlockRule) {
                        Text("+")
                    }
                    AppScreen.HOME -> Unit
                }
            }
        },
    ) { paddingValues ->
        when (currentScreen) {
            AppScreen.HOME -> HomeScreen(
                paddingValues = paddingValues,
                vpnStatusMessage = localError ?: vpnStatus.message,
                isError = localError != null || vpnStatus.state == VpnState.ERROR,
                isRunning = isRunning,
                logs = logs,
                onToggleVpn = {
                    localError = null
                    if (isRunning) {
                        viewModel.stopVpn()
                    } else {
                        try {
                            onStartRequested()
                        } catch (exception: IllegalArgumentException) {
                            localError = exception.message
                        }
                    }
                },
                onClearLogs = viewModel::clearLogs,
            )

            AppScreen.DNS_SERVERS -> DnsServersScreen(
                paddingValues = paddingValues,
                servers = servers,
                editable = !isRunning,
                onUpdate = viewModel::updateServer,
                onToggle = viewModel::toggleServer,
                onRemove = viewModel::removeServer,
            )

            AppScreen.LOCAL_RECORDS -> LocalRecordsScreen(
                paddingValues = paddingValues,
                records = localRecords,
                editable = !isRunning,
                onUpdate = viewModel::updateLocalRecord,
                onToggle = viewModel::toggleLocalRecord,
                onRemove = viewModel::removeLocalRecord,
            )

            AppScreen.BLOCK_RULES -> BlockRulesScreen(
                paddingValues = paddingValues,
                rules = blockRules,
                editable = !isRunning,
                importStatus = importStatus,
                onUpdate = viewModel::updateBlockRule,
                onToggle = viewModel::toggleBlockRule,
                onRemove = viewModel::removeBlockRule,
                onClearAll = viewModel::clearAllBlockRules,
                onImportDefault = viewModel::updateDefaultBlockRules,
                onImportFromUri = viewModel::importBlockRulesFromUri,
                onImportFromUrl = viewModel::importBlockRulesFromUrl,
                onClearImportStatus = viewModel::clearImportStatus,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    paddingValues: PaddingValues,
    vpnStatusMessage: String,
    isError: Boolean,
    isRunning: Boolean,
    logs: List<AppLogEntry>,
    onToggleVpn: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = vpnStatusMessage,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ),
            onClick = onToggleVpn,
        ) {
            Text(if (isRunning) "停止" else "启动")
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("运行日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClearLogs, enabled = logs.isNotEmpty()) {
                Text("清空")
            }
        }
        Text(
            text = "仅保存在内存中，最多 100 条最新记录，进程结束后自动清空",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (logs.isEmpty()) {
            Text(
                text = "暂无日志。启动 VPN 后会显示解析日志、VPN 状态与异常信息。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(logs, key = AppLogEntry::id) { entry ->
                    LogLine(entry)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: AppLogEntry) {
    val timeText = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestampMs))
    }
    val levelLabel = when (entry.level) {
        LogLevel.INFO -> "信息"
        LogLevel.QUERY -> "查询"
        LogLevel.SUCCESS -> "成功"
        LogLevel.ERROR -> "错误"
        LogLevel.VPN -> "VPN"
    }
    val levelColor = when (entry.level) {
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.QUERY -> MaterialTheme.colorScheme.primary
        LogLevel.SUCCESS -> Color(0xFF2E7D32)
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.VPN -> MaterialTheme.colorScheme.tertiary
    }
    Text(
        text = "$timeText [$levelLabel] ${entry.message}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = levelColor,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DnsServersScreen(
    paddingValues: PaddingValues,
    servers: List<DnsServerConfig>,
    editable: Boolean,
    onUpdate: (DnsServerConfig) -> Unit,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
    ) {
        VpnEditHint(editable, "请先停止 VPN，再编辑上游 DNS 服务器。")
        Text("上游 DNS 服务器", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "并行查询启用中的上游，取第一个成功响应。支持 UDP、HTTP、HTTPS、HTTP/3。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (servers.isEmpty()) {
            EmptyHint("尚未配置服务器，点击右下角 + 添加。")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(servers, key = DnsServerConfig::id) { server ->
                    DnsServerCard(
                        config = server,
                        editable = editable,
                        onUpdate = onUpdate,
                        onToggle = { onToggle(server.id) },
                        onRemove = { onRemove(server.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalRecordsScreen(
    paddingValues: PaddingValues,
    records: List<LocalDnsRecord>,
    editable: Boolean,
    onUpdate: (LocalDnsRecord) -> Unit,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
    ) {
        VpnEditHint(editable, "请先停止 VPN，再编辑自定义解析。")
        Text("自定义域名解析", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "优先于上游查询。支持 A / AAAA / CNAME，域名可用 * 通配，TTL 单位秒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (records.isEmpty()) {
            EmptyHint("尚未配置本地记录，点击右下角 + 添加。")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(records, key = LocalDnsRecord::id) { record ->
                    LocalRecordCard(
                        record = record,
                        editable = editable,
                        onUpdate = onUpdate,
                        onToggle = { onToggle(record.id) },
                        onRemove = { onRemove(record.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockRulesScreen(
    paddingValues: PaddingValues,
    rules: List<DomainBlockRule>,
    editable: Boolean,
    importStatus: String?,
    onUpdate: (DomainBlockRule) -> Unit,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onClearAll: () -> Unit,
    onImportDefault: () -> Unit,
    onImportFromUri: (Uri) -> Unit,
    onImportFromUrl: (String) -> Unit,
    onClearImportStatus: () -> Unit,
) {
    var showUrlDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            onImportFromUri(uri)
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("自定义 URL 导入") },
            text = {
                Column {
                    Text(
                        text = "JSON 数组，格式与 domain_block_rules.json 相同。重复规则覆盖，新规则追加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = urlText,
                        singleLine = true,
                        label = { Text("https://...") },
                        onValueChange = { urlText = it },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUrlDialog = false
                        onImportFromUrl(urlText)
                    },
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("取消") }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部拦截规则？") },
            text = { Text("将删除当前 ${rules.size} 条规则，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearAll()
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    // 顶部操作区固定，规则列表单独占满剩余高度并可滚动（LazyColumn ≈ RecyclerView）
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        VpnEditHint(editable, "请先停止 VPN，再编辑拦截规则。")
        Text("域名拦截", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "命中返回 NXDOMAIN。导入 JSON 时：重复覆盖，不重复追加。当前共 ${rules.size} 条。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (editable) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onImportDefault,
            ) { Text("更新默认规则") }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        filePicker.launch(arrayOf("text/*", "application/json", "*/*"))
                    },
                ) { Text("从文件导入") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { showUrlDialog = true },
                ) { Text("自定义 URL") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = rules.isNotEmpty(),
                onClick = { showClearConfirm = true },
            ) { Text("批量清空") }
            Spacer(Modifier.height(4.dp))
        }
        if (importStatus != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = importStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearImportStatus) { Text("关闭") }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "规则列表",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        // 独立列表区域：weight 占满剩余空间，内部 LazyColumn 回收复用 item
        if (rules.isEmpty()) {
            EmptyHint("尚未配置拦截规则。首次启动会自动写入内置规则；也可点「更新默认规则」从网络同步。")
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(
                    items = rules,
                    key = DomainBlockRule::id,
                ) { rule ->
                    BlockRuleCard(
                        rule = rule,
                        editable = editable,
                        onUpdate = onUpdate,
                        onToggle = { onToggle(rule.id) },
                        onRemove = { onRemove(rule.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VpnEditHint(editable: Boolean, message: String) {
    if (!editable) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DnsServerCard(
    config: DnsServerConfig,
    editable: Boolean,
    onUpdate: (DnsServerConfig) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EnumSelector(
                    label = "协议",
                    value = config.protocol,
                    options = DnsProtocol.entries,
                    display = DnsProtocol::displayName,
                    enabled = editable,
                    width = 150.dp,
                    onSelected = { protocol ->
                        onUpdate(
                            config.copy(
                                protocol = protocol,
                                port = protocol.defaultPort,
                            ),
                        )
                    },
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = config.enabled,
                    enabled = editable,
                    onCheckedChange = { onToggle() },
                )
                IconButton(onClick = onRemove, enabled = editable) {
                    Text("×")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = config.host,
                    enabled = editable && config.enabled,
                    singleLine = true,
                    label = { Text("主机名或 IP") },
                    onValueChange = { onUpdate(config.copy(host = it.trim())) },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    modifier = Modifier.width(96.dp),
                    value = config.port.toString(),
                    enabled = editable && config.enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("端口") },
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { port -> onUpdate(config.copy(port = port)) }
                    },
                )
            }
            if (config.protocol.usesHttpPath) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = config.path,
                    enabled = editable && config.enabled,
                    singleLine = true,
                    label = { Text("DNS HTTP 路径") },
                    onValueChange = { onUpdate(config.copy(path = it)) },
                )
            }
        }
    }
}

@Composable
private fun LocalRecordCard(
    record: LocalDnsRecord,
    editable: Boolean,
    onUpdate: (LocalDnsRecord) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EnumSelector(
                    label = "类型",
                    value = record.type,
                    options = LocalRecordType.entries,
                    display = LocalRecordType::displayName,
                    enabled = editable,
                    width = 120.dp,
                    onSelected = { onUpdate(record.copy(type = it)) },
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = record.enabled,
                    enabled = editable,
                    onCheckedChange = { onToggle() },
                )
                IconButton(onClick = onRemove, enabled = editable) {
                    Text("×")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = record.name,
                enabled = editable && record.enabled,
                singleLine = true,
                label = { Text("域名（支持 * 通配）") },
                placeholder = { Text("example.com 或 *.ads.com") },
                onValueChange = { onUpdate(record.copy(name = it.trim())) },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = record.value,
                    enabled = editable && record.enabled,
                    singleLine = true,
                    label = {
                        Text(
                            when (record.type) {
                                LocalRecordType.A -> "IPv4 地址"
                                LocalRecordType.AAAA -> "IPv6 地址"
                                LocalRecordType.CNAME -> "CNAME 目标"
                            },
                        )
                    },
                    onValueChange = { onUpdate(record.copy(value = it.trim())) },
                )
                Spacer(Modifier.width(8.dp))
                // 用本地字符串状态，允许清空/编辑中为空，不在输入过程中强制回填
                var ttlText by remember(record.id) {
                    mutableStateOf(record.ttlSeconds.toString())
                }
                LaunchedEffect(record.ttlSeconds) {
                    val parsed = ttlText.toIntOrNull()
                    if (parsed != record.ttlSeconds && ttlText.isNotEmpty()) {
                        ttlText = record.ttlSeconds.toString()
                    }
                }
                OutlinedTextField(
                    modifier = Modifier.width(100.dp),
                    value = ttlText,
                    enabled = editable && record.enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("TTL 秒") },
                    onValueChange = { value ->
                        if (value.isEmpty() || value.all { it.isDigit() }) {
                            ttlText = value
                            if (value.isNotEmpty()) {
                                value.toIntOrNull()?.let { ttl ->
                                    onUpdate(record.copy(ttlSeconds = ttl))
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BlockRuleCard(
    rule: DomainBlockRule,
    editable: Boolean,
    onUpdate: (DomainBlockRule) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EnumSelector(
                    label = "匹配方式",
                    value = rule.matchMode,
                    options = BlockMatchMode.entries,
                    display = BlockMatchMode::displayName,
                    enabled = editable,
                    width = 140.dp,
                    onSelected = { onUpdate(rule.copy(matchMode = it)) },
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = rule.enabled,
                    enabled = editable,
                    onCheckedChange = { onToggle() },
                )
                IconButton(onClick = onRemove, enabled = editable) {
                    Text("×")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = rule.pattern,
                enabled = editable && rule.enabled,
                singleLine = true,
                label = {
                    Text(
                        if (rule.matchMode == BlockMatchMode.REGEX) {
                            "正则表达式"
                        } else {
                            "域名模式（* 通配）"
                        },
                    )
                },
                placeholder = {
                    Text(
                        if (rule.matchMode == BlockMatchMode.REGEX) {
                            ".*\\.ads\\.com$"
                        } else {
                            "*.ads.com"
                        },
                    )
                },
                onValueChange = { onUpdate(rule.copy(pattern = it.trim())) },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = rule.note,
                enabled = editable && rule.enabled,
                singleLine = true,
                label = { Text("备注（可选）") },
                onValueChange = { onUpdate(rule.copy(note = it)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumSelector(
    label: String,
    value: T,
    options: List<T>,
    display: (T) -> String,
    enabled: Boolean,
    width: androidx.compose.ui.unit.Dp,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor().width(width),
            value = display(value),
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            onValueChange = {},
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}
