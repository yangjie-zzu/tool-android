package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.*
import android.content.Context
import com.yukino.tool.module.reader.common.BookContent
import com.yukino.tool.module.reader.common.BookFormat
import com.yukino.tool.module.reader.common.TxtBookContent
import com.yukino.tool.module.reader.epub.EpubBookContent
import com.yukino.tool.module.reader.epub.EpubImporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 内容加载分派: 按书格式进入各自主流程(txt 单文件全文 / epub 章节文件懒加载)。
// 两条流程在此汇合成统一的 BookContent,阅读页只消费公共接口。
// 返回 null = 缓存缺失且重导/重转存失败
object BookContents {

    suspend fun load(context: Context, book: ReaderBook): BookContent? = withContext(Dispatchers.IO) {
        when (book.format) {
            BookFormat.EPUB -> {
                val refreshed = EpubImporter.ensureCache(context, book) ?: return@withContext null
                EpubBookContent(refreshed, EpubImporter.chapterDir(context, refreshed.id))
            }
            else -> {
                val f = File(book.cachePath)
                if (f.exists() && f.length() > 0) return@withContext TxtBookContent(book, f.readText())
                val refreshed = TxtImporter.ensureCache(context, book)
                val rf = File(refreshed.cachePath)
                if (rf.exists() && rf.length() > 0) TxtBookContent(refreshed, rf.readText()) else null
            }
        }
    }
}
