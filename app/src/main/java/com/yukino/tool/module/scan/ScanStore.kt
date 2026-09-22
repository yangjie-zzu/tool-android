package com.yukino.tool.module.scan

import android.content.Context
import com.yukino.tool.db.AppDb

// 扫码记录条目: content为识别出的字符串, time为保存时间戳
data class ScanItem(
    val content: String,
    val time: Long
)

// 扫码记录持久化: SQLite scan_history表; 同内容去重(先删旧记录), 新的在前
object ScanStore {

    fun load(context: Context): MutableList<ScanItem> {
        val db = AppDb.get(context)
        val out = mutableListOf<ScanItem>()
        db.rawQuery("SELECT content, time FROM scan_history ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out.add(ScanItem(content = c.getString(0), time = c.getLong(1)))
        }
        return out
    }

    // 保存并返回更新后的列表: 相同内容先删旧记录再插到最前(去重且最新在上)
    fun save(context: Context, content: String): MutableList<ScanItem> = saveAll(context, listOf(content))

    // 批量保存: 新码逐个插到最前(列表内新码顺序=帧内识别顺序), 返回更新后的列表
    fun saveAll(context: Context, contents: List<String>): MutableList<ScanItem> {
        val items = load(context)
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            for (content in contents) {
                db.execSQL("DELETE FROM scan_history WHERE content = ?", arrayOf(content))
                val st = db.compileStatement("INSERT INTO scan_history(content, time) VALUES(?,?)")
                st.bindString(1, content)
                st.bindLong(2, System.currentTimeMillis())
                st.executeInsert()
                items.removeAll { it.content == content }
                items.add(0, ScanItem(content = content, time = System.currentTimeMillis()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return items
    }
}
