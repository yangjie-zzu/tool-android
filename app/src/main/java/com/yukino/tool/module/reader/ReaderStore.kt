package com.yukino.tool.module.reader

import android.content.Context
import androidx.core.database.getStringOrNull
import com.yukino.tool.db.AppDb
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// reader_book/reader_settings/reader_specs 三表读写, 与备忘录/浏览器共用 tool.db。
// 正文文本缓存仍是文件(ReaderBook.cachePath), 不进库。
object ReaderStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun loadBooks(context: Context): MutableList<ReaderBook> {
        val out = mutableListOf<ReaderBook>()
        AppDb.get(context).rawQuery(
            "SELECT id, title, source_uri, cache_path, encoding, total_chars, chapters, " +
                "added_at, last_read_at, progress_offset, progress_percent, file_size FROM reader_book",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ReaderBook(
                        id = c.getString(0),
                        title = c.getString(1),
                        sourceUri = c.getString(2),
                        cachePath = c.getString(3),
                        encoding = c.getString(4),
                        totalChars = c.getLong(5),
                        chapters = runCatching {
                            json.decodeFromString<List<ChapterIndex>>(c.getString(6))
                        }.getOrDefault(emptyList()), // 脏数据兜底: 无目录
                        addedAt = c.getLong(7),
                        lastReadAt = c.getLong(8),
                        progress = Progress(
                            globalCharOffset = c.getLong(9),
                            percent = c.getDouble(10)
                        ),
                        fileSize = c.getLong(11)
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun saveBooks(context: Context, books: List<ReaderBook>) {
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM reader_book")
            books.forEach { b ->
                val st = db.compileStatement(
                    "INSERT INTO reader_book(id, title, source_uri, cache_path, encoding, total_chars, " +
                        "chapters, added_at, last_read_at, progress_offset, progress_percent, file_size) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
                )
                st.bindString(1, b.id)
                st.bindString(2, b.title)
                st.bindString(3, b.sourceUri)
                st.bindString(4, b.cachePath)
                st.bindString(5, b.encoding)
                st.bindLong(6, b.totalChars)
                st.bindString(7, json.encodeToString(b.chapters))
                st.bindLong(8, b.addedAt)
                st.bindLong(9, b.lastReadAt)
                st.bindLong(10, b.progress.globalCharOffset)
                st.bindDouble(11, b.progress.percent)
                st.bindLong(12, b.fileSize)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun loadSettings(context: Context): ReaderSettings {
        AppDb.get(context).rawQuery(
            "SELECT theme, custom_bg, custom_fg, font_size_dp, line_spacing, paragraph_spacing, " +
                "margin_dp, indent, justify, keep_screen_on FROM reader_settings WHERE id = 1",
            null
        ).use { c ->
            if (!c.moveToFirst()) return ReaderSettings()
            return runCatching {
                ReaderSettings(
                    theme = ReaderTheme.valueOf(c.getString(0)),
                    customBg = if (c.isNull(1)) null else c.getLong(1),
                    customFg = if (c.isNull(2)) null else c.getLong(2),
                    fontSizeDp = c.getFloat(3),
                    lineSpacingPercent = c.getInt(4),
                    paragraphSpacingPercent = c.getInt(5),
                    marginDp = c.getInt(6),
                    indent = c.getInt(7) != 0,
                    justify = c.getInt(8) != 0,
                    keepScreenOn = c.getInt(9) != 0
                )
            }.getOrElse { ReaderSettings() } // 脏数据兜底: 回退默认设置
        }
    }

    @Synchronized
    fun saveSettings(context: Context, settings: ReaderSettings) {
        val st = AppDb.get(context).compileStatement(
            "INSERT OR REPLACE INTO reader_settings(id, theme, custom_bg, custom_fg, font_size_dp, " +
                "line_spacing, paragraph_spacing, margin_dp, indent, justify, keep_screen_on) " +
                "VALUES(1,?,?,?,?,?,?,?,?,?,?)"
        )
        st.bindString(1, settings.theme.name)
        if (settings.customBg != null) st.bindLong(2, settings.customBg) else st.bindNull(2)
        if (settings.customFg != null) st.bindLong(3, settings.customFg) else st.bindNull(3)
        st.bindDouble(4, settings.fontSizeDp.toDouble())
        st.bindLong(5, settings.lineSpacingPercent.toLong())
        st.bindLong(6, settings.paragraphSpacingPercent.toLong())
        st.bindLong(7, settings.marginDp.toLong())
        st.bindLong(8, if (settings.indent) 1 else 0)
        st.bindLong(9, if (settings.justify) 1 else 0)
        st.bindLong(10, if (settings.keepScreenOn) 1 else 0)
        st.executeInsert()
    }

    // 整本分页结果缓存: key = 版式指纹 typoKey + 全文字符数(内容变即失效)。
    // 命中则二次进入免整本重排;未命中/失效返回 null 由调用方重排后 saveSpecs 覆盖
    @Synchronized
    fun loadSpecs(context: Context, bookId: String, typoKey: Int, totalChars: Long): List<PageSpec>? =
        runCatching {
            AppDb.get(context).rawQuery(
                "SELECT typo_key, total_chars, specs FROM reader_specs WHERE book_id = ?",
                arrayOf(bookId)
            ).use { c ->
                if (!c.moveToFirst()) return null
                if (c.getInt(0) != typoKey || c.getLong(1) != totalChars) null
                else json.decodeFromString<List<PageSpec>>(c.getString(2))
            }
        }.getOrNull()

    @Synchronized
    fun saveSpecs(context: Context, bookId: String, typoKey: Int, totalChars: Long, specs: List<PageSpec>) {
        runCatching {
            val st = AppDb.get(context).compileStatement(
                "INSERT OR REPLACE INTO reader_specs(book_id, typo_key, total_chars, specs) VALUES(?,?,?,?)"
            )
            st.bindString(1, bookId)
            st.bindLong(2, typoKey.toLong())
            st.bindLong(3, totalChars)
            st.bindString(4, json.encodeToString(specs))
            st.executeInsert()
        }
    }

    @Synchronized
    fun deleteSpecs(context: Context, bookId: String) {
        AppDb.get(context).execSQL("DELETE FROM reader_specs WHERE book_id = ?", arrayOf(bookId))
    }
}
