package com.lhstack.suxi.dns.dns

import android.content.Context
import com.lhstack.suxi.dns.model.DnsServerConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * DNS 上游配置持久化。
 * 首次文件由 [DnsServersInitializer] 在 Application 启动时写入；
 * 本类只负责读已有文件与写回。
 */
class DnsServerStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, DnsServersInitializer.LOCAL_FILE_NAME)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<DnsServerConfig> {
        // 双保险：若 Application 尚未初始化，补一次（不覆盖已有文件）
        DnsServersInitializer.ensureInitialized(appContext)
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) {
                emptyList()
            } else {
                val migrated = dropRemovedProtocols(text)
                val servers = json.decodeFromString(
                    ListSerializer(DnsServerConfig.serializer()),
                    migrated,
                )
                if (migrated != text) {
                    save(servers)
                }
                servers
            }
        } catch (exception: Exception) {
            AppLog.error("读取 DNS 配置失败: ${exception.message}")
            emptyList()
        }
    }

    fun save(servers: List<DnsServerConfig>) {
        val text = json.encodeToString(ListSerializer(DnsServerConfig.serializer()), servers)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    /**
     * 从历史配置中剔除已移除的协议（如 QUIC/DoQ），避免反序列化失败。
     */
    private fun dropRemovedProtocols(text: String): String {
        val element = json.parseToJsonElement(text)
        if (element !is JsonArray) return text
        val kept = element.filter { item ->
            val protocol = item.jsonObject["protocol"]?.jsonPrimitive?.content
            protocol != null && protocol !in REMOVED_PROTOCOLS
        }
        if (kept.size == element.size) return text
        return JsonArray(kept).toString()
    }

    companion object {
        private val REMOVED_PROTOCOLS = setOf("QUIC")
    }
}
