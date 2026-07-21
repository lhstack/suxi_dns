package com.lhstack.suxi.dns.dns

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    INFO,
    QUERY,
    SUCCESS,
    ERROR,
    VPN,
}

data class AppLogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val message: String,
)

/**
 * 仅保存在进程内存中的日志缓冲，最多保留最新 [MAX_ENTRIES] 条。
 * 超出后删除最旧记录；进程结束后全部丢弃，不落盘。
 */
object AppLog {
    private const val MAX_ENTRIES = 100

    private val nextId = AtomicLong(0)
    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = _entries.asStateFlow()

    fun info(message: String) = append(LogLevel.INFO, message)

    fun query(message: String) = append(LogLevel.QUERY, message)

    fun success(message: String) = append(LogLevel.SUCCESS, message)

    fun error(message: String) = append(LogLevel.ERROR, message)

    fun vpn(message: String) = append(LogLevel.VPN, message)

    fun clear() {
        _entries.value = emptyList()
    }

    @Synchronized
    private fun append(level: LogLevel, message: String) {
        val entry = AppLogEntry(
            id = nextId.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            message = message,
        )
        val updated = _entries.value + entry
        _entries.value = if (updated.size > MAX_ENTRIES) {
            updated.takeLast(MAX_ENTRIES)
        } else {
            updated
        }
    }
}
