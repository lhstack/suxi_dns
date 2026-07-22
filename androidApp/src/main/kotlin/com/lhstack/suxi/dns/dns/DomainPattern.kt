package com.lhstack.suxi.dns.dns

import com.lhstack.suxi.dns.model.BlockMatchMode
import com.lhstack.suxi.dns.model.DomainBlockRule
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

fun normalizeDnsName(name: String): String {
    return name.trim().trimEnd('.').lowercase()
}

/** 将通配符模式转为正则：`*` → `.*`，其余元字符转义。 */
fun wildcardToRegex(pattern: String): Regex {
    val normalized = normalizeDnsName(pattern)
    val escaped = buildString {
        for (ch in normalized) {
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> {
                    append('\\')
                    append(ch)
                }
                else -> append(ch)
            }
        }
    }
    return Regex("^$escaped$", RegexOption.IGNORE_CASE)
}

class DomainBlockMatcher(rules: List<DomainBlockRule>) {
    private val compiled: List<Pair<DomainBlockRule, Regex>> = rules
        .filter(DomainBlockRule::enabled)
        .mapNotNull { rule ->
            val regex = try {
                when (rule.matchMode) {
                    BlockMatchMode.WILDCARD -> wildcardToRegex(rule.pattern)
                    BlockMatchMode.REGEX -> Regex(rule.pattern, RegexOption.IGNORE_CASE)
                }
            } catch (_: PatternSyntaxException) {
                null
            }
            regex?.let { rule to it }
        }

    fun findMatch(domain: String): DomainBlockRule? {
        val name = normalizeDnsName(domain)
        if (name.isEmpty() || name.startsWith('(')) return null
        return compiled.firstOrNull { (_, regex) -> regex.matches(name) }?.first
    }
}

class LocalNameMatcher {
    private val cache = ConcurrentHashMap<String, Regex>()

    fun matches(pattern: String, domain: String): Boolean {
        val name = normalizeDnsName(domain)
        val pat = normalizeDnsName(pattern)
        if (pat == name) return true
        if (!pat.contains('*') && !pat.contains('?')) return false
        val regex = cache.getOrPut(pat) { wildcardToRegex(pat) }
        return regex.matches(name)
    }
}
