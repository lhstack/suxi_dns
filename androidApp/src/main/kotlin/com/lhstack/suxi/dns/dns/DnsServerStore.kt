package com.lhstack.suxi.dns.dns

import android.content.Context
import com.lhstack.suxi.dns.model.DnsProtocol
import com.lhstack.suxi.dns.model.DnsServerConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * DNS 上游配置持久化。
 * 磁盘 JSON + 调用方内存 StateFlow；启动时读入，增删改后写回。
 */
class DnsServerStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<DnsServerConfig> {
        if (!file.exists()) {
            val defaults = defaultServers()
            save(defaults)
            return defaults
        }
        return try {
            val text = file.readText()
            if (text.isBlank()) {
                defaultServers().also(::save)
            } else {
                val migrated = dropRemovedProtocols(text)
                val servers = json.decodeFromString(
                    ListSerializer(DnsServerConfig.serializer()),
                    migrated,
                )
                if (migrated != text) {
                    save(servers)
                }
                servers.ifEmpty { defaultServers().also(::save) }
            }
        } catch (exception: Exception) {
            AppLog.error("读取 DNS 配置失败，使用默认配置: ${exception.message}")
            defaultServers().also(::save)
        }
    }

    fun save(servers: List<DnsServerConfig>) {
        val text = json.encodeToString(ListSerializer(DnsServerConfig.serializer()), servers)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
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
        private const val FILE_NAME = "dns_servers.json"
        private val REMOVED_PROTOCOLS = setOf("QUIC")

        fun defaultServers(): List<DnsServerConfig> = listOf(
            DnsServerConfig(
                id = 1,
                protocol = DnsProtocol.UDP,
                host = "223.5.5.5",
                port = DnsProtocol.UDP.defaultPort,
            ),
        )
    }
}
