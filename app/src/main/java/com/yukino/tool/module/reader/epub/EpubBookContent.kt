package com.yukino.tool.module.reader.epub

import com.yukino.tool.module.reader.common.BookContent
import com.yukino.tool.module.reader.common.ChapterDocument
import kotlin.math.min

// EPUB 内容源: 章节文件懒加载(小 LRU)+ 章区间切片取选中文本。
// 章区间约定见 BookContent: [startChar, startChar + 章正文长度),章间一个虚拟换行偏移。
// 章文件经 ChapterFileCodec 读(二期 JSON 含 Run/图片;一期纯文本自动回退),
// 图片段落 imageRef 由章内相对路径解析为解压目录内绝对路径(排版/渲染直接用)
class EpubBookContent(
    private val book: com.yukino.tool.module.reader.common.ReaderBook,
    private val chapterDir: java.io.File
) : BookContent {

    private val chapters = book.chapters

    override val bookTitle: String get() = book.title
    override val totalChars: Long get() = book.totalChars
    override val chapterCount: Int get() = chapters.size
    override val coverPath: String? get() = book.coverPath?.takeIf { java.io.File(it).exists() }

    override fun chapterTitle(index: Int): String = chapters[index].title

    override fun chapterStart(index: Int): Long = chapters[index].startChar

    // 章文档缓存: 与 BookPager 的行模型缓存独立(此处省重复磁盘读,后者省重复断行)
    private val docCache = object : LinkedHashMap<Int, ChapterDocument>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterDocument>) = size > 4
    }

    override fun chapterDoc(index: Int): ChapterDocument = synchronized(this) {
        docCache.getOrPut(index) {
            val f = java.io.File(chapterDir, "chapters/ch_%04d.txt".format(index))
            val paras = ChapterFileCodec.read(f)
            // 图片相对路径 → 绝对路径(一次转换,排版/渲染零路径解析)
            val resolved = if (paras.any { it.isImage }) {
                paras.map { p ->
                    if (p.isImage && p.imageRef?.startsWith("/") != true) {
                        com.yukino.tool.module.reader.common.Paragraph(
                            p.text, p.runs, p.kind, java.io.File(chapterDir, p.imageRef!!).absolutePath
                        )
                    } else p
                }
            } else paras
            ChapterDocument(chapters[index].title, resolved)
        }
    }

    // 图片像素尺寸: 只读文件头(decodeBounds),排版断行时按版心宽换算占位高
    override fun imageBounds(imageRef: String): android.graphics.Rect? = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(imageRef, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            android.graphics.Rect(0, 0, opts.outWidth, opts.outHeight)
        } else null
    }.getOrNull()

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
