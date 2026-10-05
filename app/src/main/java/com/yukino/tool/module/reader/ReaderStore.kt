package com.yukino.tool.module.reader

import android.content.Context
import com.yukino.tool.db.AppDb
import com.yukino.tool.module.reader.common.BookFormat
import com.yukino.tool.module.reader.common.ChapterIndex
import com.yukino.tool.module.reader.common.PageSpec
import com.yukino.tool.module.reader.common.Progress
import com.yukino.tool.module.reader.common.ReaderBook
import com.yukino.tool.module.reader.common.ReaderGroup
import com.yukino.tool.module.reader.common.ReaderSettings
import com.yukino.tool.module.reader.common.ReaderTheme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// reader 三表 + 分组表读写, 与备忘录/浏览器共用 tool.db。
// 写入全部单行化(upsert/delete): 导入/进度/删除都只动一行,
// 不再做全量重写(books.json 时代遗留, 批量导入会放大成 N² 写入)。
// 正文文本缓存仍是文件(reader_book.cache_path), 不进库。
object ReaderStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val BOOK_COLUMNS =
        "id, title, source_uri, cache_path, encoding, total_chars, chapters, " +
            "added_at, last_read_at, progress_offset, progress_percent, file_size, " +
            "format, author, cover_path, group_id, ready"

    private fun ReaderBook.bind(st: android.database.sqlite.SQLiteStatement) {
        st.bindString(1, id)
        st.bindString(2, title)
        st.bindString(3, sourceUri)
        st.bindString(4, cachePath)
        st.bindString(5, encoding)
        st.bindLong(6, totalChars)
        st.bindString(7, json.encodeToString(chapters))
        st.bindLong(8, addedAt)
        st.bindLong(9, lastReadAt)
        st.bindLong(10, progress.globalCharOffset)
        st.bindDouble(11, progress.percent)
        st.bindLong(12, fileSize)
        st.bindString(13, format)
        if (author != null) st.bindString(14, author) else st.bindNull(14)
        if (coverPath != null) st.bindString(15, coverPath) else st.bindNull(15)
        if (groupId != null) st.bindString(16, groupId) else st.bindNull(16)
        st.bindLong(17, if (ready) 1 else 0)
    }

    @Synchronized
    fun loadBooks(context: Context): MutableList<ReaderBook> {
        val out = mutableListOf<ReaderBook>()
        AppDb.get(context).rawQuery("SELECT $BOOK_COLUMNS FROM reader_book", null).use { c ->
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
                        fileSize = c.getLong(11),
                        format = if (c.isNull(12)) BookFormat.TXT else c.getString(12),
                        author = if (c.isNull(13)) null else c.getString(13),
                        coverPath = if (c.isNull(14)) null else c.getString(14),
                        groupId = if (c.isNull(15)) null else c.getString(15),
                        ready = c.isNull(16) || c.getInt(16) != 0
                    )
                )
            }
        }
        return out
    }

    // 单行 upsert: 导入登记/进度落盘/懒初始化回填共用
    @Synchronized
    fun upsertBook(context: Context, book: ReaderBook) {
        val st = AppDb.get(context).compileStatement(
            "INSERT OR REPLACE INTO reader_book($BOOK_COLUMNS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )
        book.bind(st)
        st.executeInsert()
    }

    // 单行删除(reader_specs 靠外键级联);缓存文件由调用方清理
    @Synchronized
    fun deleteBookRow(context: Context, bookId: String) {
        AppDb.get(context).execSQL("DELETE FROM reader_book WHERE id = ?", arrayOf(bookId))
    }

    // 批量登记(文件夹导入): 单事务多行, 一次 fsync
    @Synchronized
    fun upsertBooks(context: Context, books: List<ReaderBook>) {
        if (books.isEmpty()) return
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR REPLACE INTO reader_book($BOOK_COLUMNS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            books.forEach { it.bind(st); st.executeInsert() }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- 分组 ----------

    @Synchronized
    fun loadGroups(context: Context): List<ReaderGroup> {
        val out = mutableListOf<ReaderGroup>()
        AppDb.get(context).rawQuery(
            "SELECT id, name, parent_id, added_at, source_uri FROM reader_group", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ReaderGroup(
                        id = c.getString(0),
                        name = c.getString(1),
                        parentId = if (c.isNull(2)) null else c.getString(2),
                        addedAt = c.getLong(3),
                        sourceUri = if (c.isNull(4)) null else c.getString(4)
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun upsertGroup(context: Context, group: ReaderGroup) {
        val st = AppDb.get(context).compileStatement(
            "INSERT OR REPLACE INTO reader_group(id, name, parent_id, added_at, source_uri) VALUES(?,?,?,?,?)"
        )
        st.bindString(1, group.id)
        st.bindString(2, group.name)
        if (group.parentId != null) st.bindString(3, group.parentId) else st.bindNull(3)
        st.bindLong(4, group.addedAt)
        if (group.sourceUri != null) st.bindString(5, group.sourceUri) else st.bindNull(5)
        st.executeInsert()
    }

    @Synchronized
    fun upsertGroups(context: Context, groups: List<ReaderGroup>) {
        if (groups.isEmpty()) return
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR REPLACE INTO reader_group(id, name, parent_id, added_at, source_uri) VALUES(?,?,?,?,?)"
            )
            groups.forEach { g ->
                st.bindString(1, g.id)
                st.bindString(2, g.name)
                if (g.parentId != null) st.bindString(3, g.parentId) else st.bindNull(3)
                st.bindLong(4, g.addedAt)
                if (g.sourceUri != null) st.bindString(5, g.sourceUri) else st.bindNull(5)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // 删组及组内(各层)书籍: 返回被删的书(调用方负责清理其缓存文件)。specs 靠外键级联
    @Synchronized
    fun deleteGroupsDeep(context: Context, groupIds: List<String>): List<ReaderBook> {
        if (groupIds.isEmpty()) return emptyList()
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            val placeholders = groupIds.joinToString(",") { "?" }
            val deleted = ArrayList<ReaderBook>()
            db.rawQuery(
                "SELECT $BOOK_COLUMNS FROM reader_book WHERE group_id IN ($placeholders)",
                groupIds.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    deleted.add(
                        ReaderBook(
                            id = c.getString(0), title = c.getString(1),
                            sourceUri = c.getString(2), cachePath = c.getString(3),
                            encoding = c.getString(4), totalChars = c.getLong(5),
                            chapters = emptyList(),
                            addedAt = c.getLong(7), lastReadAt = c.getLong(8),
                            fileSize = c.getLong(11), format = c.getString(12),
                            groupId = c.getString(15)
                        )
                    )
                }
            }
            db.execSQL(
                "DELETE FROM reader_book WHERE group_id IN ($placeholders)",
                groupIds.toTypedArray()
            )
            db.execSQL(
                "DELETE FROM reader_group WHERE id IN ($placeholders)",
                groupIds.toTypedArray()
            )
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
        }
    }

    // 删组行(子组行由调用方递归收集后一并传参);组内书籍回归未分组
    @Synchronized
    fun deleteGroups(context: Context, groupIds: List<String>) {
        if (groupIds.isEmpty()) return
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            groupIds.forEach { id ->
                db.execSQL("UPDATE reader_book SET group_id = NULL WHERE group_id = ?", arrayOf(id))
                db.execSQL("DELETE FROM reader_group WHERE id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // 书移动分组(移动到未分组传 null)
    @Synchronized
    fun moveBookToGroup(context: Context, bookId: String, groupId: String?) {
        val st = AppDb.get(context).compileStatement(
            "UPDATE reader_book SET group_id = ? WHERE id = ?"
        )
        if (groupId != null) st.bindString(1, groupId) else st.bindNull(1)
        st.bindString(2, bookId)
        st.executeUpdateDelete()
    }

    @Synchronized
    fun loadSettings(context: Context): ReaderSettings {
        AppDb.get(context).rawQuery(
            "SELECT theme, custom_bg, custom_fg, font_size_dp, line_spacing, paragraph_spacing, " +
                "margin_dp, indent, justify, keep_screen_on, book_spacing, book_line_height FROM reader_settings WHERE id = 1",
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
                    keepScreenOn = c.getInt(9) != 0,
                    bookSpacing = c.isNull(10) || c.getInt(10) != 0,
                    bookLineHeight = c.isNull(11) || c.getInt(11) != 0
                )
            }.getOrElse { ReaderSettings() } // 脏数据兜底: 回退默认设置
        }
    }

    @Synchronized
    fun saveSettings(context: Context, settings: ReaderSettings) {
        val st = AppDb.get(context).compileStatement(
            "INSERT OR REPLACE INTO reader_settings(id, theme, custom_bg, custom_fg, font_size_dp, " +
                "line_spacing, paragraph_spacing, margin_dp, indent, justify, keep_screen_on, book_spacing, book_line_height) " +
                "VALUES(1,?,?,?,?,?,?,?,?,?,?,?,?)"
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
        st.bindLong(11, if (settings.bookSpacing) 1 else 0)
        st.bindLong(12, if (settings.bookLineHeight) 1 else 0)
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
