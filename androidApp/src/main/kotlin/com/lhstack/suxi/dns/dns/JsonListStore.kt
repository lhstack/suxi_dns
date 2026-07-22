package com.lhstack.suxi.dns.dns

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 通用 JSON 列表持久化（应用私有目录）。 */
class JsonListStore<T>(
    context: Context,
    private val fileName: String,
    private val itemSerializer: KSerializer<T>,
    private val defaultItems: () -> List<T> = { emptyList() },
) {
    private val file = File(context.applicationContext.filesDir, fileName)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val listSerializer = ListSerializer(itemSerializer)

    fun load(): List<T> {
        if (!file.exists()) {
            val defaults = defaultItems()
            if (defaults.isNotEmpty()) save(defaults)
            return defaults
        }
        return try {
            val text = file.readText()
            if (text.isBlank()) {
                defaultItems().also { if (it.isNotEmpty()) save(it) }
            } else {
                json.decodeFromString(listSerializer, text)
            }
        } catch (exception: Exception) {
            AppLog.error("读取 $fileName 失败: ${exception.message}")
            defaultItems().also { if (it.isNotEmpty()) save(it) }
        }
    }

    fun save(items: List<T>) {
        val text = json.encodeToString(listSerializer, items)
        val temp = File(file.parentFile, "$fileName.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }
}
