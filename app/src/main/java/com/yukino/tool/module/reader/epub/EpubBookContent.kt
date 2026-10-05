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

    // 七期批次三: 全书字体文件表(family → 绝对路径;打开各章时按章内 fontPaths 逐章合并)
    private val fontFiles = HashMap<String, String>()

    // family → 字体文件绝对路径(未知 family 返回 null,绘制回落默认字体)
    fun fontFile(family: String): String? = synchronized(fontFiles) { fontFiles[family] }

    // 章文档缓存: 与 BookPager 的行模型缓存独立(此处省重复磁盘读,后者省重复断行)
    private val docCache = object : LinkedHashMap<Int, ChapterDocument>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterDocument>) = size > 4
    }

    override fun chapterDoc(index: Int): ChapterDocument = synchronized(this) {
        docCache.getOrPut(index) {
            val f = java.io.File(chapterDir, "chapters/ch_%04d.txt".format(index))
            val read = ChapterFileCodec.read(f)
            val paras = read.paragraphs
            val footnotes = read.notes
            // 七期批次三: 章内字体表(family → 相对路径)绝对化(重复登记幂等,首见为准)
            val docFontFiles = LinkedHashMap<String, String>()
            synchronized(fontFiles) {
                for ((family, rel) in read.fontPaths) {
                    val abs = if (rel.startsWith("/")) rel else java.io.File(chapterDir, rel).absolutePath
                    docFontFiles[family] = abs
                    if (!fontFiles.containsKey(family)) fontFiles[family] = abs
                }
            }
            // 图片相对路径 → 绝对路径(一次转换,排版/渲染零路径解析;块级 imageRef、
            // 行内 inlineImages 与表格格内 imgRef 同规则)
            val needResolve = paras.any {
                it.isImage || it.inlineImages.isNotEmpty() ||
                    it.table?.cells?.any { c -> c.imgRef != null } == true
            }
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
                        // 命名参数补全全部字段: 图片段也可能带 table 之外的段级状态,
                        // 位置构造截止 boxStyle 会静默丢字段
                        com.yukino.tool.module.reader.common.Paragraph(
                            p.text, p.runs, p.kind, java.io.File(chapterDir, p.imageRef!!).absolutePath,
                            anchor = p.anchor, notes = p.notes, align = p.align,
                            indentEm = p.indentEm, indentCss = p.indentCss,
                            spaceAboveEm = p.spaceAboveEm, spaceBelowEm = p.spaceBelowEm,
                            heading = p.heading, inlineImages = inline,
                            marginLeftEm = p.marginLeftEm, marginRightEm = p.marginRightEm,
                            widthEm = p.widthEm, widthAlign = p.widthAlign,
                            lineSpacingMult = p.lineSpacingMult, boxStyle = boxAbs(p.boxStyle),
                            table = absTable(p.table), floatSide = p.floatSide,
                            breakAll = p.breakAll, brBefore = p.brBefore
                        )
                    } else if (inline !== p.inlineImages || p.boxStyle != boxAbs(p.boxStyle) ||
                        p.table !== absTable(p.table)
                    ) {
                        com.yukino.tool.module.reader.common.Paragraph(
                            p.text, p.runs, p.kind, p.imageRef,
                            anchor = p.anchor, notes = p.notes, align = p.align,
                            indentEm = p.indentEm, indentCss = p.indentCss,
                            spaceAboveEm = p.spaceAboveEm, spaceBelowEm = p.spaceBelowEm,
                            heading = p.heading, inlineImages = inline,
                            marginLeftEm = p.marginLeftEm, marginRightEm = p.marginRightEm,
                            widthEm = p.widthEm, widthAlign = p.widthAlign,
                            lineSpacingMult = p.lineSpacingMult, boxStyle = boxAbs(p.boxStyle),
                            table = absTable(p.table), floatSide = p.floatSide,
                            breakAll = p.breakAll, brBefore = p.brBefore
                        )
                    } else p
                }
            } else if (paras.any { it.boxStyle != boxAbs(it.boxStyle) }) {
                paras.map { it.copyBox(boxAbs(it.boxStyle)) }
            } else paras
            ChapterDocument(
                chapters[index].title, resolved, footnotes, read.fonts, docFontFiles,
                read.cssHrefs, read.cssInline
            )
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
            text, runs, kind, imageRef,
            anchor = anchor, notes = notes, align = align,
            indentEm = indentEm, indentCss = indentCss,
            spaceAboveEm = spaceAboveEm, spaceBelowEm = spaceBelowEm,
            heading = heading, inlineImages = inlineImages,
            marginLeftEm = marginLeftEm, marginRightEm = marginRightEm,
            widthEm = widthEm, widthAlign = widthAlign,
            lineSpacingMult = lineSpacingMult, boxStyle = bs,
            table = absTable(table), floatSide = floatSide, breakAll = breakAll, brBefore = brBefore
        )

    // 表格格内图片相对路径 → 绝对路径(无格内图或已绝对化原样返回同实例)
    private fun absTable(td: com.yukino.tool.module.reader.common.TableData?): com.yukino.tool.module.reader.common.TableData? {
        if (td == null || td.cells.none { it.imgRef != null && !it.imgRef.startsWith("/") }) return td
        val cells = td.cells.map { c ->
            if (c.imgRef != null && !c.imgRef.startsWith("/")) {
                com.yukino.tool.module.reader.common.TableCell(
                    c.row, c.col, c.rowSpan, c.colSpan, c.text, c.runs,
                    c.align, c.vAlign, c.bg, c.edges, c.header,
                    java.io.File(chapterDir, c.imgRef).absolutePath
                )
            } else c
        }
        return com.yukino.tool.module.reader.common.TableData(
            td.rows, td.cols, cells, td.collapse, td.spacingEm, td.colWidths
        )
    }

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

    // ---- 装饰章(七期): 判定复用章文档缓存;快照落盘于书目录 deco/,按需存在性检查 ----

    override fun isDecorative(chapterIndex: Int): Boolean =
        chapterIndex in chapters.indices &&
            chapterDoc(chapterIndex).paragraphs.firstOrNull()?.boxStyle != null

    // 混合渲染: WEBVIEW 块位图缓存根目录 = 解压根(章文件目录即解压根)
    override fun webBlockRoot(): java.io.File = chapterDir

    override fun decoSnapshot(chapterIndex: Int): String? {
        if (!isDecorative(chapterIndex)) return null
        return EpubImporter.decoFileOf(chapterDir, chapterIndex).takeIf { it.exists() }?.absolutePath
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
