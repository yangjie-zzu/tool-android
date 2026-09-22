package com.yukino.tool.module.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.database.sqlite.SQLiteDatabase
import com.yukino.tool.db.AppDb

// 浏览历史条目: icon为48px PNG原始字节(站点favicon), 无图标为null
data class WebHistoryItem(
    val url: String,
    val title: String = "",
    val time: Long,
    val icon: ByteArray? = null
) {
    fun iconBitmap(): Bitmap? = runCatching {
        icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }.getOrNull()

    override fun equals(other: Any?): Boolean =
        other is WebHistoryItem && url == other.url && title == other.title &&
            time == other.time && (icon contentEquals other.icon)

    override fun hashCode(): Int = (url.hashCode() * 31 + title.hashCode()) * 31 + time.hashCode()
}

// 浏览历史持久化: SQLite web_history表, 不设条目上限(新的在前)
object WebHistoryStore {

    fun load(context: Context): MutableList<WebHistoryItem> {
        val db = AppDb.get(context)
        val out = mutableListOf<WebHistoryItem>()
        db.rawQuery("SELECT url, title, time, icon FROM web_history ORDER BY time DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    WebHistoryItem(
                        url = c.getString(0),
                        title = c.getString(1),
                        time = c.getLong(2),
                        icon = if (c.isNull(3)) null else c.getBlob(3)
                    )
                )
            }
        }
        return out
    }

    // 全量覆写(调用方持有全量列表, 每次改动整表替换)
    fun save(context: Context, items: List<WebHistoryItem>) {
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM web_history")
            db.execSQL("DELETE FROM sqlite_sequence WHERE name = 'web_history'")
            items.forEach { insert(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear(context: Context) {
        AppDb.get(context).execSQL("DELETE FROM web_history")
    }

    private fun insert(db: SQLiteDatabase, item: WebHistoryItem) {
        val st = db.compileStatement("INSERT OR REPLACE INTO web_history(url, title, time, icon) VALUES(?,?,?,?)")
        st.bindString(1, item.url)
        st.bindString(2, item.title)
        st.bindLong(3, item.time)
        if (item.icon != null) st.bindBlob(4, item.icon) else st.bindNull(4)
        st.executeInsert()
    }

    // favicon压缩为48px PNG字节
    fun compressIcon(bitmap: Bitmap): ByteArray? = runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
        out.toByteArray()
    }.getOrNull()
}
