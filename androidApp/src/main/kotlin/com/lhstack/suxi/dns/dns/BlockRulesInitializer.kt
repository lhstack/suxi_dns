package com.lhstack.suxi.dns.dns

import android.content.Context
import com.lhstack.suxi.dns.model.DomainBlockRule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 拦截规则本地文件的首次初始化。
 *
 * 仅在 `filesDir/domain_block_rules.json` **不存在** 时，
 * 从 APK 内置 `assets/block_rules.json` 复制并落盘。
 * 已有文件则绝不覆盖（用户改过的配置始终优先）。
 */
object BlockRulesInitializer {
    const val LOCAL_FILE_NAME = "domain_block_rules.json"
    const val BUNDLED_ASSET = "block_rules.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
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
            val rules = loadBundled(context)
            if (rules.isEmpty()) {
                AppLog.error("内置拦截规则为空，跳过初始化")
                return false
            }
            save(file, rules)
            AppLog.info("首次启动：已从内置规则初始化 ${rules.size} 条拦截规则")
            true
        } catch (exception: Exception) {
            AppLog.error("初始化内置拦截规则失败: ${exception.message}")
            false
        }
    }

    fun loadBundled(context: Context): List<DomainBlockRule> {
        val text = context.assets.open(BUNDLED_ASSET)
            .bufferedReader()
            .use { it.readText() }
        return json.decodeFromString(ListSerializer(DomainBlockRule.serializer()), text)
    }

    private fun save(file: File, rules: List<DomainBlockRule>) {
        val text = json.encodeToString(ListSerializer(DomainBlockRule.serializer()), rules)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }
}
