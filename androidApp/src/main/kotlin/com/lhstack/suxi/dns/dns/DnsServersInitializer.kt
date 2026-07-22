package com.lhstack.suxi.dns.dns

import android.content.Context
import com.lhstack.suxi.dns.model.DnsProtocol
import com.lhstack.suxi.dns.model.DnsServerConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * DNS 上游配置首次初始化。
 *
 * 仅在 `filesDir/dns_servers.json` **不存在** 时写入默认四条上游。
 * 已有文件绝不覆盖。
 */
object DnsServersInitializer {
    const val LOCAL_FILE_NAME = "dns_servers.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * @return true 表示本次执行了初始化；false 表示本地文件已存在，跳过。
     */
    fun ensureInitialized(context: Context): Boolean {
        val file = File(context.applicationContext.filesDir, LOCAL_FILE_NAME)
        if (file.exists() && file.length() > 0L) {
            return false
        }
        return try {
            val defaults = defaultServers()
            save(file, defaults)
            AppLog.info("首次启动：已初始化 ${defaults.size} 个 DNS 上游")
            true
        } catch (exception: Exception) {
            AppLog.error("初始化默认 DNS 上游失败: ${exception.message}")
            false
        }
    }

    fun defaultServers(): List<DnsServerConfig> = listOf(
        DnsServerConfig(
            id = 1,
            protocol = DnsProtocol.UDP,
            host = "223.5.5.5",
            port = DnsProtocol.UDP.defaultPort,
        ),
        DnsServerConfig(
            id = 2,
            protocol = DnsProtocol.UDP,
            host = "223.6.6.6",
            port = DnsProtocol.UDP.defaultPort,
        ),
        DnsServerConfig(
            id = 3,
            protocol = DnsProtocol.HTTP3,
            host = "223.5.5.5",
            port = DnsProtocol.HTTP3.defaultPort,
            path = "/dns-query",
        ),
        DnsServerConfig(
            id = 4,
            protocol = DnsProtocol.HTTP3,
            host = "223.6.6.6",
            port = DnsProtocol.HTTP3.defaultPort,
            path = "/dns-query",
        ),
    )

    private fun save(file: File, servers: List<DnsServerConfig>) {
        val text = json.encodeToString(ListSerializer(DnsServerConfig.serializer()), servers)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }
}
