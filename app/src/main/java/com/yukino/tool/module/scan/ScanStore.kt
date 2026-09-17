package com.yukino.tool.module.scan

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 扫码记录条目: content为识别出的字符串, time为保存时间戳
@Serializable
data class ScanItem(
    val content: String,
    val time: Long
)

// 扫码记录持久化: SharedPreferences存JSON; 同内容去重(先删旧记录), 新的在前
object ScanStore {
    private const val PREFS = "scan_history"
    private const val KEY = "items"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): MutableList<ScanItem> = runCatching {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        json.decodeFromString<List<ScanItem>>(s).toMutableList()
    }.getOrDefault(mutableListOf())

    // 保存并返回更新后的列表: 相同内容先删旧记录再插到最前(去重且最新在上)
    fun save(context: Context, content: String): MutableList<ScanItem> {
        val items = load(context)
        items.removeAll { it.content == content }
        items.add(0, ScanItem(content = content, time = System.currentTimeMillis()))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.encodeToString(items.toList())).apply()
        return items
    }
}
