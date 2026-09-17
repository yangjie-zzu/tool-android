package com.yukino.tool.module.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 浏览历史条目: icon为48px PNG的Base64(站点favicon), 无图标为null
@Serializable
data class WebHistoryItem(
    val url: String,
    val title: String = "",
    val time: Long,
    val icon: String? = null
) {
    fun iconBitmap(): Bitmap? = runCatching {
        val data = icon ?: return null
        val bytes = Base64.decode(data, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

// 浏览历史持久化: SharedPreferences存JSON, 不设条目上限(新的在前)
object WebHistoryStore {
    private const val PREFS = "web_history"
    private const val KEY = "items"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): MutableList<WebHistoryItem> = runCatching {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        json.decodeFromString<List<WebHistoryItem>>(s).toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(context: Context, items: List<WebHistoryItem>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.encodeToString(items.toList())).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    // favicon压缩为48px PNG的Base64
    fun compressIcon(bitmap: Bitmap): String? = runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
