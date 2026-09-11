package com.yukino.tool.module.reader

import android.content.Context
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// books.json / settings.json 读写(整读整写,文件仅几 KB),与备忘录 note.json 同风格
object ReaderStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(context: Context): File = File(context.filesDir, "reader").apply { mkdirs() }
    private fun booksFile(context: Context): File = File(dir(context), "books.json")
    private fun settingsFile(context: Context): File = File(dir(context), "settings.json")

    @Synchronized
    fun loadBooks(context: Context): MutableList<ReaderBook> {
        val f = booksFile(context)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            json.decodeFromString<MutableList<ReaderBook>>(f.readText())
        }.getOrElse { mutableListOf() } // 脏数据兜底: 回退空书架
    }

    @Synchronized
    fun saveBooks(context: Context, books: List<ReaderBook>) {
        booksFile(context).writeText(json.encodeToString(books))
    }

    @Synchronized
    fun loadSettings(context: Context): ReaderSettings {
        val f = settingsFile(context)
        if (!f.exists()) return ReaderSettings()
        return runCatching { json.decodeFromString<ReaderSettings>(f.readText()) }
            .getOrElse { ReaderSettings() }
    }

    @Synchronized
    fun saveSettings(context: Context, settings: ReaderSettings) {
        settingsFile(context).writeText(json.encodeToString(settings))
    }

    // 整本分页结果缓存: key = 版式指纹 typoKey + 全文字符数(内容变即失效)。
    // 命中则二次进入免整本重排;未命中/失效返回 null 由调用方重排后 saveSpecs 覆盖
    @kotlinx.serialization.Serializable
    private data class SpecsCache(val typoKey: Int, val totalChars: Long, val specs: List<PageSpec>)

    private fun specsFile(context: Context, bookId: String): File =
        File(File(dir(context), "specs").apply { mkdirs() }, "$bookId.json")

    @Synchronized
    fun loadSpecs(context: Context, bookId: String, typoKey: Int, totalChars: Long): List<PageSpec>? =
        runCatching {
            val f = specsFile(context, bookId)
            if (!f.exists()) return null
            val c = json.decodeFromString<SpecsCache>(f.readText())
            if (c.typoKey != typoKey || c.totalChars != totalChars) null else c.specs
        }.getOrNull()

    @Synchronized
    fun saveSpecs(context: Context, bookId: String, typoKey: Int, totalChars: Long, specs: List<PageSpec>) {
        runCatching {
            specsFile(context, bookId).writeText(json.encodeToString(SpecsCache(typoKey, totalChars, specs)))
        }
    }

    @Synchronized
    fun deleteSpecs(context: Context, bookId: String) {
        specsFile(context, bookId).delete()
    }
}
