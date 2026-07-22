package com.lhstack.suxi.dns.model

import kotlinx.serialization.Serializable

/**
 * 域名拦截规则。
 * - [WILDCARD]：`*` 匹配任意字符序列，如 `*.ads.com`、`*tracker*`
 * - [REGEX]：完整 Java/Kotlin 正则，匹配整个域名（忽略大小写）
 */
@Serializable
enum class BlockMatchMode(val displayName: String) {
    WILDCARD("通配符 *"),
    REGEX("正则"),
}

@Serializable
data class DomainBlockRule(
    val id: Long,
    val pattern: String,
    val matchMode: BlockMatchMode = BlockMatchMode.WILDCARD,
    val enabled: Boolean = true,
    val note: String = "",
) {
    fun validate() {
        require(pattern.isNotBlank()) { "拦截规则不能为空" }
        if (matchMode == BlockMatchMode.REGEX) {
            runCatching { Regex(pattern) }.getOrElse {
                throw IllegalArgumentException("正则无效: ${it.message}")
            }
        }
    }

    val displaySummary: String
        get() = buildString {
            append(if (matchMode == BlockMatchMode.REGEX) "re:" else "")
            append(pattern)
            if (note.isNotBlank()) append(" （$note）")
        }
}
