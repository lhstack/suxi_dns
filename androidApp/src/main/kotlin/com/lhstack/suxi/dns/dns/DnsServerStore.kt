package com.lhstack.suxi.dns.dns

import android.content.Context
import com.lhstack.suxi.dns.model.DnsServerConfig
import com.lhstack.suxi.dns.model.DnsServerGroup
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * DNS 上游分组配置持久化。
 * 首次文件由 [DnsServersInitializer] 在 Application 启动时写入；
 * 本类只负责读已有文件与写回。
 *
 * 兼容旧格式：旧版本文件是扁平的「服务器数组」（JSON 顶层元素含 protocol 字段），
 * load 时自动迁移为单个分组并写回，避免老用户丢配置。
 */
class DnsServerStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, DnsServersInitializer.LOCAL_FILE_NAME)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<DnsServerGroup> {
        // 双保险：若 Application 尚未初始化，补一次（不覆盖已有文件）
        DnsServersInitializer.ensureInitialized(appContext)
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) {
                emptyList()
            } else {
                decode(text)
            }
        } catch (exception: Exception) {
            AppLog.error("读取 DNS 配置失败: ${exception.message}")
            emptyList()
        }
    }

    fun save(groups: List<DnsServerGroup>) {
        val text = json.encodeToString(ListSerializer(DnsServerGroup.serializer()), groups)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    /**
     * 解析配置。
     * - 顶层元素含 `servers` 字段 → 新的分组格式，仅剔除已移除协议后直接反序列化。
     * - 顶层元素含 `protocol` 字段 → 旧的扁平服务器数组，迁移为单分组并写回。
     */
    private fun decode(text: String): List<DnsServerGroup> {
        val element = json.parseToJsonElement(text)
        if (element !is JsonArray || element.isEmpty()) {
            return decodeGroups(text)
        }
        val first = element.first().jsonObject
        return if (first.containsKey("servers")) {
            decodeGroups(dropRemovedProtocolsInGroups(text))
        } else {
            migrateFlatServers(element)
        }
    }

    private fun decodeGroups(text: String): List<DnsServerGroup> {
        val groups = json.decodeFromString(
            ListSerializer(DnsServerGroup.serializer()),
            text,
        )
        if (dropRemovedProtocolsInGroups(text) != text) {
            save(groups)
        }
        return groups
    }

    /** 旧扁平数组 → 单个默认分组，并立即写回新格式。 */
    private fun migrateFlatServers(element: JsonArray): List<DnsServerGroup> {
        val kept = element.filter { item ->
            val protocol = item.jsonObject["protocol"]?.jsonPrimitive?.content
            protocol != null && protocol !in REMOVED_PROTOCOLS
        }
        val servers = json.decodeFromString(
            ListSerializer(DnsServerConfig.serializer()),
            JsonArray(kept).toString(),
        )
        val migrated = listOf(
            DnsServerGroup(
                id = 1,
                name = "首选",
                servers = servers,
            ),
        )
        save(migrated)
        AppLog.info("已将旧版 DNS 配置迁移为分组格式（${servers.size} 个上游）")
        return migrated
    }

    /**
     * 从分组配置中剔除已移除的协议（如 QUIC/DoQ），避免反序列化失败。
     * 返回可能被改写的 JSON 文本；若无变化则原样返回。
     */
    private fun dropRemovedProtocolsInGroups(text: String): String {
        val element = json.parseToJsonElement(text)
        if (element !is JsonArray) return text
        var changed = false
        val groups = element.map { groupElement ->
            val group = groupElement.jsonObject
            val serversElement = group["servers"] as? JsonArray ?: return@map groupElement
            val keptServers = serversElement.filter { item ->
                val protocol = item.jsonObject["protocol"]?.jsonPrimitive?.content
                protocol != null && protocol !in REMOVED_PROTOCOLS
            }
            if (keptServers.size == serversElement.size) {
                groupElement
            } else {
                changed = true
                buildGroupWithServers(group, keptServers)
            }
        }
        if (!changed) return text
        return JsonArray(groups).toString()
    }

    private fun buildGroupWithServers(
        group: Map<String, kotlinx.serialization.json.JsonElement>,
        servers: List<kotlinx.serialization.json.JsonElement>,
    ): kotlinx.serialization.json.JsonElement {
        val mutable = group.toMutableMap()
        mutable["servers"] = JsonArray(servers)
        return kotlinx.serialization.json.JsonObject(mutable)
    }

    companion object {
        private val REMOVED_PROTOCOLS = setOf("QUIC")
    }
}
