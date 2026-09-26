package com.yukino.tool.module.web

import android.content.Context
import com.yukino.tool.db.AppDb

// 下载任务持久化记录: web_download表, 下载页面的数据源
data class DownloadRecord(
    val url: String,
    val name: String,
    val status: String,
    val offset: Long,
    val total: Long,
    val mime: String? = null,
    val fileUri: String? = null,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

// 下载记录持久化: SQLite web_download表(状态迁移时单条写入, 新的在前)
object DownloadStore {

    fun load(context: Context): MutableList<DownloadRecord> {
        val db = AppDb.get(context)
        val out = mutableListOf<DownloadRecord>()
        db.rawQuery(
            "SELECT url, name, status, offset, total, mime, file_uri, error, updated_at FROM web_download ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DownloadRecord(
                        url = c.getString(0),
                        name = c.getString(1),
                        status = c.getString(2),
                        offset = c.getLong(3),
                        total = c.getLong(4),
                        mime = if (c.isNull(5)) null else c.getString(5),
                        fileUri = if (c.isNull(6)) null else c.getString(6),
                        error = if (c.isNull(7)) null else c.getString(7),
                        updatedAt = c.getLong(8)
                    )
                )
            }
        }
        return out
    }

    fun upsert(context: Context, item: DownloadRecord) {
        val db = AppDb.get(context)
        val st = db.compileStatement(
            "INSERT OR REPLACE INTO web_download(url, name, status, offset, total, mime, file_uri, error, updated_at) VALUES(?,?,?,?,?,?,?,?,?)"
        )
        synchronized(this) {
            st.bindString(1, item.url)
            st.bindString(2, item.name)
            st.bindString(3, item.status)
            st.bindLong(4, item.offset)
            st.bindLong(5, item.total)
            if (item.mime != null) st.bindString(6, item.mime) else st.bindNull(6)
            if (item.fileUri != null) st.bindString(7, item.fileUri) else st.bindNull(7)
            if (item.error != null) st.bindString(8, item.error) else st.bindNull(8)
            st.bindLong(9, System.currentTimeMillis())
            st.executeInsert()
        }
    }

    fun remove(context: Context, url: String) {
        AppDb.get(context).execSQL("DELETE FROM web_download WHERE url = ?", arrayOf(url))
    }
}
