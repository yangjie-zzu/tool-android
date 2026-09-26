package com.yukino.tool.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/*
 * 全应用唯一 SQLite 库(tool.db)。四个模块的持久化都收在这里:
 *   note_meta/note_entry/note_field  备忘录(加密元数据+条目+字段)
 *   reader_book/reader_settings/reader_specs  阅读(书架+设置+分页缓存)
 *   web_history/scan_history  浏览历史/扫码记录
 *   web_download  web模块下载任务列表
 *   migration_flag  旧数据→SQLite 的一次性迁移标记(每模块一行)
 * 正文文本缓存仍在 files/(books.cache_path 指向)，不进库。
 */
object AppDb {

    private const val NAME = "tool.db"
    private const val VERSION = 2

    @Volatile
    private var helper: SQLiteOpenHelper? = null

    fun get(context: Context): SQLiteDatabase {
        helper?.let { return it.writableDatabase }
        synchronized(this) {
            helper?.let { return it.writableDatabase }
            val app = context.applicationContext
            val h = object : SQLiteOpenHelper(app, NAME, null, VERSION) {
                override fun onCreate(db: SQLiteDatabase) = Schema.create(db)
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // 首版无历史版本; 后续 schema 变更在这里按 oldVersion 逐级 ALTER
                    if (oldVersion < 2) {
                        db.execSQL(Schema.WEB_DOWNLOAD)
                    }
                }
            }
            helper = h
            return h.writableDatabase
        }
    }

    private object Schema {
        fun create(db: SQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys = ON")
            // 备忘录: 加密元数据(盐/主密码校验值/指纹封存), 一行一个 k/v, 二进制 Base64 存文本
            db.execSQL(
                "CREATE TABLE note_meta (" +
                    "k TEXT PRIMARY KEY NOT NULL, " +
                    "v TEXT NOT NULL)"
            )
            // 备忘录条目: ord 保持原有列表顺序(新条目追加在末尾, 与旧 JSON 数组顺序一致)
            db.execSQL(
                "CREATE TABLE note_entry (" +
                    "id INTEGER PRIMARY KEY NOT NULL, " +
                    "ord INTEGER NOT NULL)"
            )
            // 备忘录字段: secret 字段的 value 是"Base64(IV)|Base64(密文)", 明文字段存原文
            db.execSQL(
                "CREATE TABLE note_field (" +
                    "entry_id INTEGER NOT NULL REFERENCES note_entry(id) ON DELETE CASCADE, " +
                    "idx INTEGER NOT NULL, " +
                    "key TEXT NOT NULL, " +
                    "value TEXT NOT NULL, " +
                    "secret INTEGER NOT NULL, " +
                    "totp INTEGER NOT NULL, " +
                    "is_title INTEGER NOT NULL, " +
                    "preview INTEGER NOT NULL, " +
                    "PRIMARY KEY (entry_id, idx))"
            )
            // 书架
            db.execSQL(
                "CREATE TABLE reader_book (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "source_uri TEXT NOT NULL, " +
                    "cache_path TEXT NOT NULL, " +
                    "encoding TEXT NOT NULL, " +
                    "total_chars INTEGER NOT NULL, " +
                    "chapters TEXT NOT NULL, " + // 章节列表整体存 JSON, 只随书整体读写
                    "added_at INTEGER NOT NULL, " +
                    "last_read_at INTEGER NOT NULL, " +
                    "progress_offset INTEGER NOT NULL DEFAULT 0, " +
                    "progress_percent REAL NOT NULL DEFAULT 0, " +
                    "file_size INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL("CREATE INDEX idx_book_last_read ON reader_book(last_read_at DESC)")
            // 阅读设置: 恒单行
            db.execSQL(
                "CREATE TABLE reader_settings (" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                    "theme TEXT NOT NULL, " +
                    "custom_bg INTEGER, " +
                    "custom_fg INTEGER, " +
                    "font_size_dp REAL NOT NULL, " +
                    "line_spacing INTEGER NOT NULL, " +
                    "paragraph_spacing INTEGER NOT NULL, " +
                    "margin_dp INTEGER NOT NULL, " +
                    "indent INTEGER NOT NULL, " +
                    "justify INTEGER NOT NULL, " +
                    "keep_screen_on INTEGER NOT NULL)"
            )
            // 分页缓存: 派生数据, 版式/内容变化由 typo_key/total_chars 失效
            db.execSQL(
                "CREATE TABLE reader_specs (" +
                    "book_id TEXT PRIMARY KEY NOT NULL REFERENCES reader_book(id) ON DELETE CASCADE, " +
                    "typo_key INTEGER NOT NULL, " +
                    "total_chars INTEGER NOT NULL, " +
                    "specs TEXT NOT NULL)"
            )
            // 浏览历史: url 主键天然去重; icon 为 48px PNG 原始字节
            db.execSQL(
                "CREATE TABLE web_history (" +
                    "url TEXT PRIMARY KEY NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "time INTEGER NOT NULL, " +
                    "icon BLOB)"
            )
            db.execSQL("CREATE INDEX idx_web_time ON web_history(time DESC)")
            // 扫码记录: content 唯一索引保证同内容去重
            db.execSQL(
                "CREATE TABLE scan_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "content TEXT NOT NULL, " +
                    "time INTEGER NOT NULL)"
            )
            db.execSQL("CREATE UNIQUE INDEX idx_scan_content ON scan_history(content)")
            // web下载任务列表
            db.execSQL(WEB_DOWNLOAD)
            db.execSQL("CREATE INDEX idx_dl_updated ON web_download(updated_at DESC)")
            // 迁移标记: 每模块一行, 有行即该模块已迁完
            db.execSQL(
                "CREATE TABLE migration_flag (" +
                    "module TEXT PRIMARY KEY NOT NULL, " +
                    "done_at INTEGER NOT NULL)"
            )
        }

        // v2: web下载任务列表(下载页面数据源)
        const val WEB_DOWNLOAD =
            "CREATE TABLE web_download (" +
                "url TEXT PRIMARY KEY NOT NULL, " +
                "name TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +   // RUNNING/PAUSED/INTERRUPTED/FAILED/DONE
                "offset INTEGER NOT NULL, " +
                "total INTEGER NOT NULL, " +
                "mime TEXT, " +
                "file_uri TEXT, " +          // 完成后用于打开
                "error TEXT, " +
                "updated_at INTEGER NOT NULL)"
    }
}
