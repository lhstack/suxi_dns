package com.lhstack.suxi.dns.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.lhstack.suxi.dns.model.DnsProtocol
import com.lhstack.suxi.dns.model.DnsServerConfig
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
    val vpnStatus by viewModel.vpnStatus.collectAsState()
    val logs by viewModel.logs.collectAsState()
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
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
        floatingActionButton = {
            if (currentScreen == AppScreen.DNS_SERVERS && !isRunning) {
                FloatingActionButton(onClick = viewModel::addServer) {
                    Text("+")
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
        if (!editable) {
            Text(
                text = "请先停止 VPN，再编辑上游 DNS 服务器。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
        }
        Text("上游 DNS 服务器", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "并行查询启用中的上游，取第一个成功响应。支持 UDP、HTTP、HTTPS、HTTP/3。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (servers.isEmpty()) {
            Text(
                text = "尚未配置服务器，点击右下角 + 添加。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                ProtocolSelector(
                    protocol = config.protocol,
                    enabled = editable,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolSelector(
    protocol: DnsProtocol,
    enabled: Boolean,
    onSelected: (DnsProtocol) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor().width(150.dp),
            value = protocol.displayName,
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            label = { Text("协议") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            onValueChange = {},
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DnsProtocol.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}
