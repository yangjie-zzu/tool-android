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
            val (paras, footnotes) = ChapterFileCodec.read(f)
            // 图片相对路径 → 绝对路径(一次转换,排版/渲染零路径解析;块级 imageRef 与行内 inlineImages 同规则)
            val needResolve = paras.any { it.isImage || it.inlineImages.isNotEmpty() }
            val resolved = if (needResolve) {
                paras.map { p ->
                    val inline = if (p.inlineImages.isNotEmpty()) {
                        p.inlineImages.map { im ->
                            val abs = if (im.ref.startsWith("/")) im.ref
                            else java.io.File(chapterDir, im.ref).absolutePath
                            com.yukino.tool.module.reader.common.InlineImg(im.start, abs)
                        }
                    } else p.inlineImages
                    if (p.isImage && p.imageRef?.startsWith("/") != true) {
                        com.yukino.tool.module.reader.common.Paragraph(
                            p.text, p.runs, p.kind, java.io.File(chapterDir, p.imageRef!!).absolutePath,
                            p.anchor, p.notes, p.align, p.indentEm, p.spaceAboveEm, p.spaceBelowEm,
                            p.heading, inline, p.marginLeftEm, p.marginRightEm, p.widthEm,
                            p.widthCenter, p.lineSpacingMult, boxAbs(p.boxStyle)
                        )
                    } else if (inline !== p.inlineImages || p.boxStyle != boxAbs(p.boxStyle)) {
                        com.yukino.tool.module.reader.common.Paragraph(
                            p.text, p.runs, p.kind, p.imageRef,
                            p.anchor, p.notes, p.align, p.indentEm, p.spaceAboveEm, p.spaceBelowEm,
                            p.heading, inline, p.marginLeftEm, p.marginRightEm, p.widthEm,
                            p.widthCenter, p.lineSpacingMult, boxAbs(p.boxStyle)
                        )
                    } else p
                }
            } else if (paras.any { it.boxStyle != boxAbs(it.boxStyle) }) {
                paras.map { it.copyBox(boxAbs(it.boxStyle)) }
            } else paras
            ChapterDocument(chapters[index].title, resolved, footnotes)
        }
    }

    // 盒背景图相对路径 → 绝对路径(无背景图原样返回同实例,避免无谓拷贝)
    private fun boxAbs(bs: com.yukino.tool.module.reader.common.BoxStyle?): com.yukino.tool.module.reader.common.BoxStyle? {
        if (bs == null) return null
        val img = bs.bgImage ?: return bs
        if (img.startsWith("/")) return bs
        return bs.copy(bgImage = java.io.File(chapterDir, img).absolutePath)
    }

    private fun com.yukino.tool.module.reader.common.Paragraph.copyBox(
        bs: com.yukino.tool.module.reader.common.BoxStyle?
    ): com.yukino.tool.module.reader.common.Paragraph =
        com.yukino.tool.module.reader.common.Paragraph(
            text, runs, kind, imageRef, anchor, notes, align, indentEm, spaceAboveEm, spaceBelowEm,
            heading, inlineImages, marginLeftEm, marginRightEm, widthEm, widthCenter, lineSpacingMult, bs
        )

    // 章内锚点 → 投影偏移: 扫段落 anchor 匹配,偏移 = 前序段长累计(与 bodyText 同构)
    override fun anchorOffset(chapterIndex: Int, anchorId: String): Long? {
        if (chapterIndex !in chapters.indices) return null
        val paras = chapterDoc(chapterIndex).paragraphs
        var off = 0L
        for (p in paras) {
            if (p.anchor == anchorId) return off
            off += p.text.length + 1L   // 段间一个换行
        }
        return null
    }

    // 全书偏移 → 角标脚注: 二分章 → 章内偏移 → 段区间 → 段内角标区间匹配
    override fun footnoteAt(globalOffset: Long): Pair<String, String>? {
        var lo = 0
        var hi = chapters.lastIndex
        if (hi < 0) return null
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chapters[mid].startChar <= globalOffset) lo = mid else hi = mid - 1
        }
        val inPara = (globalOffset - chapters[lo].startChar).coerceAtLeast(0L)
        var segStart = 0L
        for (p in chapterDoc(lo).paragraphs) {
            val segEnd = segStart + p.text.length
            if (inPara in segStart until segEnd) {
                val local = (inPara - segStart).toInt()
                val hit = p.notes.firstOrNull { local >= it.start && local < it.end } ?: return null
                val text = chapterDoc(lo).footnotes[hit.noteId] ?: return null
                return hit.noteId to text
            }
            segStart = segEnd + 1L   // 段间换行
        }
        return null
    }

    // 图片像素尺寸: 位图只读文件头(decodeBounds),SVG 解析矢量尺寸;
    // 排版断行时按版心宽换算占位高
    override fun imageBounds(imageRef: String): android.graphics.Rect? = runCatching {
        if (com.yukino.tool.module.reader.common.SvgDecoder.isSvg(imageRef)) {
            return@runCatching com.yukino.tool.module.reader.common.SvgDecoder.bounds(imageRef)
        }
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
