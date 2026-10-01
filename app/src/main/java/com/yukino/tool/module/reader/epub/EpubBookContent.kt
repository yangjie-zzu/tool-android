package com.yukino.tool.module.reader.epub

import com.yukino.tool.module.reader.common.BookContent
import com.yukino.tool.module.reader.common.ChapterDocument
import kotlin.math.min

// EPUB 内容源: 章节文件懒加载(小 LRU)+ 章区间切片取选中文本。
// 章区间约定见 BookContent: [startChar, startChar + 章正文长度),章间一个虚拟换行偏移
class EpubBookContent(
    private val book: com.yukino.tool.module.reader.common.ReaderBook,
    private val chapterDir: java.io.File
) : BookContent {

    private val chapters = book.chapters

    override val bookTitle: String get() = book.title
    override val totalChars: Long get() = book.totalChars
    override val chapterCount: Int get() = chapters.size

    override fun chapterTitle(index: Int): String = chapters[index].title

    override fun chapterStart(index: Int): Long = chapters[index].startChar

    // 章文档缓存: 与 BookPager 的行模型缓存独立(此处省重复磁盘读,后者省重复断行)
    private val docCache = object : LinkedHashMap<Int, ChapterDocument>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterDocument>) = size > 4
    }

    override fun chapterDoc(index: Int): ChapterDocument = synchronized(this) {
        docCache.getOrPut(index) {
            val f = java.io.File(chapterDir, "chapters/ch_%04d.txt".format(index))
            ChapterDocument(chapters[index].title, f.readText().split('\n').map { com.yukino.tool.module.reader.common.Paragraph(it) })
        }
    }

    override fun textAt(startGlobal: Long, endGlobal: Long): String = synchronized(this) {
        val s = startGlobal.coerceIn(0, totalChars)
        val e = endGlobal.coerceIn(s, totalChars)
        if (e <= s) return ""
        val sb = StringBuilder()
        var pendingBreak = false
        for (i in 0 until chapterCount) {
            val st = chapters[i].startChar
            if (st >= e) break
            val body = chapterDoc(i).bodyText
            val len = body.length.toLong()
            val cs = maxOf(s, st)
            val ce = min(e, st + len)
            if (ce > cs) {
                if (pendingBreak) sb.append('\n')
                sb.append(body.substring((cs - st).toInt(), (ce - st).toInt()))
                pendingBreak = true
            }
        }
        sb.toString()
    }
}
