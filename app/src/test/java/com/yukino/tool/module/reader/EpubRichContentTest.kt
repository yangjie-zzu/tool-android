package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.ChapterLines
import com.yukino.tool.module.reader.common.CssLen
import com.yukino.tool.module.reader.common.ChapterComposer
import com.yukino.tool.module.reader.common.BookPager
import com.yukino.tool.module.reader.common.LineKind
import com.yukino.tool.module.reader.common.ParaKind
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.common.Run
import com.yukino.tool.module.reader.common.RunStyle
import com.yukino.tool.module.reader.common.TextLine
import com.yukino.tool.module.reader.epub.ChapterFileCodec
import com.yukino.tool.module.reader.epub.HtmlTextExtractor
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 二期富文本回归: XHTML 解析(投影不变性/Run 边界/图片/列表/表格降级)、
// 章文件 JSON 编解码(含一期纯文本回退)、段落级剥标题与字符级等价、行内样式折算。
// 全部纯 JVM(Jsoup/序列化/纯函数),不依赖 Android 运行时
class EpubRichContentTest {

    private fun extractHtml(html: String, docDir: String = "") =
        HtmlTextExtractor.extract(Jsoup.parseBodyFragment(html).body(), docDir).paragraphs

    private fun extractFull(html: String, docDir: String = "") =
        HtmlTextExtractor.extract(Jsoup.parseBodyFragment(html).body(), docDir)

    // ---- 投影不变性(与一期纯文本形态逐字符一致) ----

    @Test
    fun `普通段落投影与一期一致且无Run`() {
        val paras = extractHtml("<p>你好，世界</p><p>第二段</p>")
        assertEquals(listOf("你好，世界", "第二段"), paras.map { it.text })
        assertTrue(paras.all { it.runs.isEmpty() })
        assertTrue(paras.all { it.kind == ParaKind.TEXT })
    }

    @Test
    fun `空白规整与一期规则一致`() {
        // 连续空白折叠为一;CJK 间粘连空格清除(汉/字/之均 CJK);段首尾空白丢弃
        val paras = extractHtml("<p>  汉  字 之间   word  </p>")
        assertEquals(1, paras.size)
        assertEquals("汉字之间 word", paras[0].text)
    }

    // ---- Run 生成 ----

    @Test
    fun `粗体标签产出Run且边界正确`() {
        // Run 全区间覆盖: 样式段与其余普通段(style=0)分立,绘制层按区间无缝覆盖整行
        val paras = extractHtml("<p>前缀<b>粗体</b>后缀</p>")
        val p = paras[0]
        assertEquals("前缀粗体后缀", p.text)
        assertEquals(
            listOf(Run(0, 2, 0), Run(2, 4, RunStyle.BOLD), Run(4, 6, 0)),
            p.runs
        )
    }

    @Test
    fun `内联标签不是段落边界`() {
        val paras = extractHtml("<p>前<b>粗</b>后</p>")
        assertEquals(1, paras.size)
        assertEquals("前粗后", paras[0].text)
    }

    @Test
    fun `样式切换Run不重叠且相邻同样式合并`() {
        // <b>汉</b> <b>字</b>: 中间空格被 CJK 规则清除,两段加粗合并为同一 Run
        val paras = extractHtml("<p><b>汉</b> <b>字</b></p>")
        assertEquals("汉字", paras[0].text)
        assertEquals(listOf(Run(0, 2, RunStyle.BOLD)), paras[0].runs)
    }

    @Test
    fun `斜体接粗体分属两个Run`() {
        val paras = extractHtml("<p><i>斜</i><b>粗</b></p>")
        assertEquals("斜粗", paras[0].text)
        assertEquals(
            listOf(Run(0, 1, RunStyle.ITALIC), Run(1, 2, RunStyle.BOLD)),
            paras[0].runs
        )
    }

    @Test
    fun `em与strong同为粗斜语义`() {
        val paras = extractHtml("<p><em>强</em><strong>壮</strong></p>")
        assertEquals(RunStyle.ITALIC, paras[0].runs[0].style)
        assertEquals(RunStyle.BOLD, paras[0].runs[1].style)
    }

    @Test
    fun `style属性叠加到Run`() {
        // 样式段与普通文本段分立(普通区间显式 style=0,绘制层按区间全覆盖)
        val paras = extractHtml("<p><span style=\"font-weight:bold\">重</span>点</p>")
        assertEquals(
            listOf(Run(0, 1, RunStyle.BOLD), Run(1, 2, 0)),
            paras[0].runs
        )
    }

    @Test
    fun `parseStyleAttr_宽容匹配`() {
        assertEquals(RunStyle.BOLD, HtmlTextExtractor.parseStyleAttr("font-weight: 700; color:red"))
        assertEquals(RunStyle.ITALIC or RunStyle.UNDERLINE, HtmlTextExtractor.parseStyleAttr("font-style:italic;text-decoration: underline"))
        assertEquals(RunStyle.STRIKE, HtmlTextExtractor.parseStyleAttr("text-decoration:line-through"))
        assertEquals(RunStyle.SUP, HtmlTextExtractor.parseStyleAttr("vertical-align:super"))
        assertEquals(RunStyle.SUB, HtmlTextExtractor.parseStyleAttr("vertical-align:sub"))
        assertEquals(0, HtmlTextExtractor.parseStyleAttr("color:red;margin:0"))
    }

    // ---- 图片段 ----

    @Test
    fun `img独立成图片段且投影占位`() {
        val paras = extractHtml("<p>文字</p><img src=\"images/1.jpg\"/><p>图后</p>")
        assertEquals(3, paras.size)
        val img = paras[1]
        assertTrue(img.isImage)
        assertEquals(ParaKind.IMAGE, img.kind)
        assertEquals(HtmlTextExtractor.IMAGE_PLACEHOLDER, img.text)
        assertEquals("images/1.jpg", img.imageRef)
        assertEquals("图后", paras[2].text)
    }

    @Test
    fun `img相对路径按文档目录解析`() {
        val paras = extractHtml("<img src=\"../img/pic.png\"/>", docDir = "OEBPS/text")
        assertEquals("OEBPS/img/pic.png", paras[0].imageRef)
    }

    @Test
    fun `外部URL与dataURI的img忽略`() {
        val paras = extractHtml("<p>a</p><img src=\"https://x/y.png\"/><img src=\"data:image/png;base64,AAAA\"/><p>b</p>")
        assertEquals(listOf("a", "b"), paras.map { it.text })
    }

    // ---- 列表/表格降级 ----

    @Test
    fun `ol序列前缀_ul圆点前缀`() {
        val paras = extractHtml("<ol><li>第一</li><li>第二</li></ol><ul><li>点项</li></ul>")
        assertEquals(listOf("1. 第一", "2. 第二", "• 点项"), paras.map { it.text })
    }

    @Test
    fun `嵌套列表计数重置`() {
        val paras = extractHtml("<ol><li>外一<ol><li>内一</li><li>内二</li></ol></li><li>外二</li></ol>")
        assertEquals(listOf("1. 外一", "1. 内一", "2. 内二", "2. 外二"), paras.map { it.text })
    }

    @Test
    fun `简单表格真渲染数据`() {
        val paras = extractHtml("<table><tr><td>a</td><td>b</td></tr><tr><td>1</td><td>2</td></tr></table>")
        assertEquals(1, paras.size)
        val p = paras[0]
        assertTrue(p.isTable)
        val td = p.table!!
        assertEquals(2, td.rows)
        assertEquals(2, td.cols)
        assertEquals(listOf("a", "b", "1", "2"), td.cells.map { it.text })
        assertEquals(listOf(0, 1, 0, 1), td.cells.map { it.col })
        assertTrue(td.collapse)
    }

    @Test
    fun `跨行列表格网格展开`() {
        val paras = extractHtml(
            "<table><tr><td rowspan=\"2\">a</td><td>b</td></tr><tr><td>c</td></tr></table>"
        )
        val td = paras[0].table!!
        assertEquals(2, td.rows)
        assertEquals(2, td.cols)
        val a = td.cells.first { it.text == "a" }
        assertEquals(0, a.row)
        assertEquals(0, a.col)
        assertEquals(2, a.rowSpan)
        // 第二行的 c 落在列 1(列 0 被跨行格占用)
        val c = td.cells.first { it.text == "c" }
        assertEquals(1, c.row)
        assertEquals(1, c.col)
    }

    @Test
    fun `表头与单元格样式`() {
        val paras = extractHtml(
            "<style>td.vm { vertical-align: top; background-color: #eee } .hl { color: #f00 }</style>" +
                "<table><tr><th>表头</th></tr><tr><td class=\"vm\"><span class=\"hl\">高亮</span></td></tr></table>"
        )
        val td = paras[0].table!!
        val th = td.cells[0]
        assertTrue(th.header)
        assertEquals(1, th.vAlign)   // th 默认居中
        val c = td.cells[1]
        assertEquals(0, c.vAlign)    // vm → top
        assertEquals(0xFFEEEEEEL, c.bg)
        // 格内富文本: color run 保留
        assertTrue(c.runs.any { it.color == 0xFFFF0000L })
    }

    @Test
    fun `表格布局列宽行高与折行`() {
        val typo = com.yukino.tool.module.reader.common.Typography.resolve(
            2f, com.yukino.tool.module.reader.common.ReaderSettings(), 800, 1200
        )
        val measure = { s: String -> s.length * 10f }
        val td = com.yukino.tool.module.reader.common.TableData(
            rows = 2, cols = 2,
            cells = listOf(
                com.yukino.tool.module.reader.common.TableCell(0, 0, text = "ab"),
                com.yukino.tool.module.reader.common.TableCell(0, 1, text = "长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长"),
                com.yukino.tool.module.reader.common.TableCell(1, 0, rowSpan = 2, colSpan = 2, text = "跨行格")
            )
        )
        val tl = BookPager.layoutTable(td, typo.textWidth.toFloat(), typo, measure)
        assertEquals(2, tl.widths.size)
        // 撑满可用宽
        assertEquals(typo.textWidth.toFloat(), tl.totalWidth, 1f)
        // "长内容×9" 自然宽超半版心被压缩 → 折行(至少 2 行);首行行高不低于内容
        val wrapCell = tl.cells.first { it.cell.text.startsWith("长") }
        assertTrue(wrapCell.lines.size >= 2)
        assertTrue(tl.heights[0] >= wrapCell.lines.size * tl.lineH + tl.padV * 2 - 1f)
        // 折行文本拼回原文(空格清理后字符一致)
        assertEquals("长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长长", wrapCell.lines.joinToString("") { it.text })
        // 跨行格底边 = 两行高之和(简化: 格高不小于内容)
        val spanCell = tl.cells.first { it.cell.rowSpan == 2 }
        assertTrue(spanCell.h >= tl.lineH + tl.padV * 2)
    }

    @Test
    fun `十期章文件表格往返`() {
        val f = File.createTempFile("ch_v10", ".txt")
        val td = com.yukino.tool.module.reader.common.TableData(
            rows = 1, cols = 2, collapse = false, spacingEm = 0.2f,
            cells = listOf(
                com.yukino.tool.module.reader.common.TableCell(
                    0, 0, text = "格A", runs = listOf(Run(0, 1, RunStyle.BOLD)),
                    align = 1, vAlign = 0, bg = 0xFFEEEEL,
                    edges = listOf(
                        com.yukino.tool.module.reader.common.EdgeStyle(1f, 1, 0xFF000000L),
                        com.yukino.tool.module.reader.common.EdgeStyle(), 
                        com.yukino.tool.module.reader.common.EdgeStyle(),
                        com.yukino.tool.module.reader.common.EdgeStyle()
                    ),
                    header = true
                ),
                com.yukino.tool.module.reader.common.TableCell(0, 1, colSpan = 1, text = "格B")
            )
        )
        val paras = listOf(Paragraph(HtmlTextExtractor.IMAGE_PLACEHOLDER, table = td))
        ChapterFileCodec.write(f, paras)
        val read = ChapterFileCodec.read(f)
        val rt = read.paragraphs[0].table!!
        assertEquals(1, rt.rows)
        assertEquals(2, rt.cols)
        assertEquals("格A", rt.cells[0].text)
        assertEquals(RunStyle.BOLD, rt.cells[0].runs[0].style and RunStyle.BOLD)
        assertEquals(1, rt.cells[0].align)
        assertEquals(0, rt.cells[0].vAlign)
        assertEquals(0xFFEEEEL, rt.cells[0].bg)
        assertEquals(1f, rt.cells[0].edges[0].widthEm)
        assertTrue(rt.cells[0].header)
        assertTrue(!rt.collapse)
        assertEquals(0.2f, rt.spacingEm)
        assertTrue(!com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(f))
        f.delete()
    }

    // ---- 章文件编解码 ----

    @Test
    fun `JSON读写含Run与图片段`() {
        val f = File.createTempFile("ch_rich", ".txt")
        val paras = listOf(
            Paragraph("普通段"),
            Paragraph("加粗斜体", listOf(Run(0, 2, RunStyle.BOLD or RunStyle.ITALIC))),
            Paragraph(HtmlTextExtractor.IMAGE_PLACEHOLDER, emptyList(), ParaKind.IMAGE, "images/1.jpg")
        )
        ChapterFileCodec.write(f, paras)
        val (read, notes1) = ChapterFileCodec.read(f)
        assertEquals(0, notes1.size)
        assertEquals(3, read.size)
        assertEquals("普通段", read[0].text)
        assertTrue(read[0].runs.isEmpty())
        assertEquals(listOf(Run(0, 2, RunStyle.BOLD or RunStyle.ITALIC)), read[1].runs)
        assertTrue(read[2].isImage)
        assertEquals("images/1.jpg", read[2].imageRef)
        f.delete()
    }

    @Test
    fun `一期纯文本章文件自动回退`() {
        val f = File.createTempFile("ch_legacy", ".txt")
        f.writeText("第一段\n第二段\n\n第四段")
        val (read, notes2) = ChapterFileCodec.read(f)
        assertEquals(0, notes2.size)
        assertEquals(listOf("第一段", "第二段", "", "第四段"), read.map { it.text })
        assertTrue(read.all { it.runs.isEmpty() && it.kind == ParaKind.TEXT })
        f.delete()
    }

    // ---- 剥标题: 段落级与字符级等价 ----

    @Test
    fun `段落级剥标题与字符级逐字符等价`() {
        val cases = listOf(
            "第一章 试炼\n正文开始\n第二行" to "第一章 试炼",
            "\n\n第一章\n\n正文" to "第一章",
            "前言\n第一章 内容\n正文" to "第一章 内容",
            "不相等的标题\n正文" to "章名"
        )
        for ((body, title) in cases) {
            val (b2, stripped) = ChapterComposer.stripLeadingTitle(body, title)
            val paras = body.split('\n').map { Paragraph(it) }
            val (p2, stripped2) = ChapterComposer.stripLeadingTitleParas(paras, title)
            assertEquals("stripped 不等: $body", stripped, stripped2)
            assertEquals("投影不等: $body", b2, p2.joinToString("\n") { it.text })
        }
    }

    // ---- 行内样式折算 ----

    private fun linesFor(vararg specs: TextLine, paras: List<Paragraph>, ranges: List<IntRange>, composed: String) =
        ChapterLines(composed, 4, 0L, specs.toList(), paras, ranges, emptyMap())

    @Test
    fun `行内样式折算跨行裁剪`() {
        val paras = listOf(Paragraph("ABCDEF", listOf(Run(0, 3, RunStyle.BOLD))))
        val cl = linesFor(
            TextLine(0, 2, LineKind.TITLE, false, 10, 0, 5),
            TextLine(4, 7, LineKind.BODY, true, 10, 0, 5),
            TextLine(7, 10, LineKind.BODY, false, 10, 0, 5),
            paras = paras, ranges = listOf(4 until 10), composed = "标题\n\nABCDEF"
        )
        // 首行(段内偏移 [0,3)): 整个 Run 落在行内
        val s1 = BookPager.lineStyles(cl, cl.lines[1], BookPager.paraIndexOf(cl, 4))
        assertEquals(listOf(LineStyleAssert(0, 3, RunStyle.BOLD)), s1!!.map { LineStyleAssert(it.start, it.end, it.style) })
        // 次行(段内偏移 [3,6)): Run 不再覆盖,退化为单一样式
        val s2 = BookPager.lineStyles(cl, cl.lines[2], BookPager.paraIndexOf(cl, 7))
        assertNull(s2)
    }

    @Test
    fun `无Run段落行折算为null`() {
        val paras = listOf(Paragraph("纯文本"))
        val cl = linesFor(
            TextLine(4, 7, LineKind.BODY, true, 10, 0, 5),
            paras = paras, ranges = listOf(4 until 7), composed = "标题\n\n纯文本"
        )
        assertNull(BookPager.lineStyles(cl, cl.lines[0], BookPager.paraIndexOf(cl, 4)))
    }

    @Test
    fun `paraIndexOf跳过空段命中相邻段`() {
        // 段落: [4,4)空段? 不——空段 range 为空区间,二分必须跳过它命中下一段
        val paras = listOf(Paragraph("AAA"), Paragraph(""), Paragraph("BBB"))
        val cl = linesFor(
            TextLine(4, 7, LineKind.BODY, true, 10, 0, 5),
            TextLine(8, 11, LineKind.BODY, false, 10, 0, 5),
            paras = paras, ranges = listOf(4 until 7, 7 until 7, 8 until 11),
            composed = "标题\n\nAAA\n\nBBB"
        )
        assertEquals(0, BookPager.paraIndexOf(cl, 5))
        assertEquals(2, BookPager.paraIndexOf(cl, 9))
        assertEquals(-1, BookPager.paraIndexOf(cl, 0))   // 标题区
    }

    // LineStyle 无 equals(普通 class),测试用三元组对比
    private data class LineStyleAssert(val start: Int, val end: Int, val style: Int)

    // ---- 六期: 外部 CSS / float 降级 / 负 margin ----

    @Test
    fun `外部CSS文件的class规则生效`() {
        val dir = File.createTempFile("cssdir", "").let { it.delete(); it.mkdirs(); it }
        val css = File(dir, "style.css")
        css.writeText(".right { text-align: right }\n.fr { float: right }")
        val html = File(dir, "ch.xhtml")
        html.writeText(
            """<html><head><link href="style.css" rel="stylesheet" type="text/css"/></head>
            <body><div class="fr"><p>浮块内容</p></div><p class="right">右对齐段</p></body></html>"""
        )
        val r = HtmlTextExtractor.extract(html, "")
        assertEquals(2, r.paragraphs[0].align)   // float:right 降级 → 右对齐
        assertEquals(2, r.paragraphs[1].align)   // .right 类
        html.delete(); css.delete(); dir.delete()
    }

    @Test
    fun `float识别_right降级_right外忽略`() {
        val r1 = HtmlTextExtractor.parseParaLayout(mapOf("float" to "right"))
        assertTrue(r1!!.floatRight)
        val lf = HtmlTextExtractor.parseParaLayout(mapOf("float" to "left"))
        assertTrue(lf!!.floatLeft && !lf.floatRight)   // 批次四b: 左浮标记(真环绕由排版期 width+height 判定)
        // float 与显式对齐同设时,显式对齐优先
        val r2 = HtmlTextExtractor.parseParaLayout(mapOf("float" to "right", "text-align" to "center"))
        assertEquals(1, r2!!.align)
        assertTrue(r2.floatRight)
    }

    @Test
    fun `负margin提取`() {
        // 四值简写 margin: top right bottom left → 上=-0.2 下=-0.1
        val l = HtmlTextExtractor.parseParaLayout(mapOf("margin" to "-0.2em 0 -0.1em 0"))
        assertEquals(CssLen(-0.2f), l!!.aboveEm)
        assertEquals(CssLen(-0.1f), l.belowEm)
        val l2 = HtmlTextExtractor.parseParaLayout(mapOf("margin-top" to "-1em", "margin-bottom" to "0.3em"))
        assertEquals(CssLen(-1f), l2!!.aboveEm)
        assertEquals(CssLen(0.3f), l2.belowEm)
        val l3 = HtmlTextExtractor.parseParaLayout(mapOf("margin" to "1em"))
        assertEquals(CssLen(1f), l3!!.aboveEm)
        assertEquals(CssLen(1f), l3.belowEm)
    }


    // ---- 五期: 行内图片(图片型脚注角标) ----

    @Test
    fun `图片型noteref投影占位并记行内图片`() {
        val r = extractFull(
            "<p>正文<a epub:type=\"noteref\" href=\"#n1\"><sup><img src=\"images/note.png\"/></sup></a>结尾。</p>" +
                "<aside epub:type=\"footnote\" id=\"n1\">图片角标的脚注。</aside>"
        )
        assertEquals(1, r.paragraphs.size)
        val p = r.paragraphs[0]
        // 投影: 正文 + U+FFFC + 结尾。
        assertEquals("正文" + HtmlTextExtractor.IMAGE_PLACEHOLDER + "结尾。", p.text)
        assertEquals(1, p.inlineImages.size)
        assertEquals(2, p.inlineImages[0].start)
        assertEquals("images/note.png", p.inlineImages[0].ref)
        // 角标区间与占位符重合,脚注可点
        assertEquals(1, p.notes.size)
        assertEquals("n1", p.notes[0].noteId)
        assertEquals(p.inlineImages[0].start, p.notes[0].start)
        // 脚注内容入表
        assertEquals("图片角标的脚注。", r.footnotes["n1"])
    }

    @Test
    fun `图片型noteref外部图忽略`() {
        val r = extractFull(
            "<p>a<a epub:type=\"noteref\" href=\"#n1\"><img src=\"https://x/n.png\"/></a>b</p>"
        )
        assertEquals(0, r.paragraphs[0].inlineImages.size)
        assertEquals("ab", r.paragraphs[0].text)
    }

    @Test
    fun `五期行内图片章文件往返`() {
        val f = File.createTempFile("ch_v5", ".txt")
        val paras = listOf(
            Paragraph("角标" + HtmlTextExtractor.IMAGE_PLACEHOLDER + "尾",
                inlineImages = listOf(com.yukino.tool.module.reader.common.InlineImg(2, "images/note.png")))
        )
        ChapterFileCodec.write(f, paras)
        val (read, _) = ChapterFileCodec.read(f)
        assertEquals(1, read[0].inlineImages.size)
        assertEquals(2, read[0].inlineImages[0].start)
        assertEquals("images/note.png", read[0].inlineImages[0].ref)
        assertTrue(!com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(f))
        f.delete()
    }


    // ---- 四期: CSS 子集 / h2 拆章 / 排版属性 ----

    @Test
    fun `CSS选择器解析_类元素复合后代子代_id与at规则跳过`() {
        val css = """
            /* 注释 .fake { text-align: right } */
            .center { text-align: center; color: red }
            div.note, .indent-2 { text-indent: 2em }
            #id-sel { text-align: right }
            p { margin-top: 0.4em }
            ul.contents { text-indent: 0em }
            li ul li.c-rules { margin-left: -1em }
            dl.logo-maker > dt { font-size: 15px }
            @font-face { font-family: title }
        """.trimIndent()
        val rules = HtmlTextExtractor.parseStyleBlock(css)
        // @font-face 跳过;#id-sel 自 v18 起解析为 spec=100 规则;div.note 与 .indent-2 独立
        assertEquals(8, rules.size)
        fun plain(tag: String?, cls: String?) =
            com.yukino.tool.module.reader.epub.HtmlTextExtractor.SimpleSel(
                tag, if (cls == null) emptySet() else setOf(cls)
            )
        // 单元素 / 单类 / 复合(tag.cls) / 后代链 / 子代链 / id
        assertEquals(plain("p", null), rules.first { it.specificity == 1 }.sel.chain.last())
        assertEquals(plain(null, "center"), rules.first { it.specificity == 10 }.sel.chain.last())
        assertEquals(plain("div", "note"), rules.first { it.specificity == 11 }.sel.chain.last())
        val desc = rules.first { it.sel.chain.size == 3 }
        assertEquals(listOf(plain("li", null), plain("ul", null), plain("li", "c-rules")), desc.sel.chain)
        assertTrue(desc.sel.childAt.isEmpty())
        val child = rules.first { it.sel.childAt.isNotEmpty() }
        assertEquals(listOf(plain("dl", "logo-maker"), plain("dt", null)), child.sel.chain)
        assertEquals(setOf(0), child.sel.childAt)
        val idRule = rules.first { it.sel.chain.last().id == "id-sel" }
        assertEquals(100, idRule.specificity)
    }

    // ---- 七期: ruby 注音 / class 规则 Run 级属性 / 元素与复合选择器生效 ----

    @Test
    fun `ruby基文本进正文_rt音译入脚注表并挂锚点`() {
        val r = extractFull(
            "<p>得了<ruby>疱疹<rt>herpes</rt></ruby>很痛。</p>"
        )
        assertEquals(1, r.paragraphs.size)
        val p = r.paragraphs[0]
        assertEquals("得了疱疹很痛。", p.text)   // 注音不混入正文
        assertEquals(1, p.notes.size)
        assertEquals("herpes", r.footnotes[p.notes[0].noteId])
        // 锚点区间 = 基文本"疱疹"在段内坐标 [2,4)
        assertEquals(2, p.notes[0].start)
        assertEquals(4, p.notes[0].end)
    }

    @Test
    fun `ruby无rt或rp括号内容剥离`() {
        val r = extractFull("<p><ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>です</p>")
        assertEquals("漢字です", r.paragraphs[0].text)
        assertEquals("かんじ", r.footnotes.values.first())
    }

    @Test
    fun `class规则的Run级属性生效`() {
        val html = """
            <style>.bold { font-weight: bold } .ita { font-style: italic }
            .postil-b { vertical-align: super; font-weight: bold }</style>
            <p class="bold">整段加粗</p>
            <p><span class="ita">斜体</span><span class="postil-b">注</span></p>
        """.trimIndent()
        val paras = extractHtml(html)
        assertEquals(2, paras.size)
        assertTrue(paras[0].runs.isNotEmpty() && paras[0].runs.all { it.style and RunStyle.BOLD != 0 })
        assertEquals(RunStyle.ITALIC, paras[1].runs[0].style and RunStyle.ITALIC)
        val s = paras[1].runs[1].style
        assertEquals(RunStyle.SUP or RunStyle.BOLD, s and (RunStyle.SUP or RunStyle.BOLD))
    }

    @Test
    fun `元素选择器生效且被类与style按优先级覆盖`() {
        val html = """
            <style>p { text-indent: 2em; text-align: justify }
            .center { text-align: center }</style>
            <p>元素规则缩进</p>
            <p class="center">类覆盖元素对齐</p>
            <p style="text-indent: 0">style覆盖元素缩进</p>
        """.trimIndent()
        val paras = extractHtml(html)
        assertEquals(CssLen(2f), paras[0].indentCss)   // p 元素规则(v18 起缩进统一 CssLen)
        assertEquals(1, paras[1].align)                // .center(类,spec 10) 覆盖 p(spec 1)
        assertEquals(CssLen(0f), paras[2].indentCss)   // style 属性最高
    }

    @Test
    fun `复合与后代子代选择器命中`() {
        val html = """
            <style>ul.contents { text-indent: 0em }
            li ul li.c-rules { margin-top: -0.5em }
            ul.direct > li { margin-top: 1em }</style>
            <div class="contents">div不命中ul.contents</div>
            <ul class="contents"><li>外层
              <ul><li class="c-rules">深层规则项</li></ul></li></ul>
            <ul class="direct"><li>直接子li</li>
              <ul><li>深层li</li></ul></ul>
        """.trimIndent()
        val paras = extractHtml(html)
        assertNull(paras[0].indentCss)                // div.contents 不命中 ul.contents
        assertEquals(CssLen(0f), paras[1].indentCss)  // ul.contents 命中
        // li.c-rules: 命中后代链(祖先链 ul→li)
        val deep = paras.first { it.text.contains("深层规则项") }
        assertEquals(CssLen(-0.5f), deep.spaceAboveEm)
        val direct = paras.first { it.text.contains("直接子li") }
        assertEquals(CssLen(1f), direct.spaceAboveEm)     // 子代: li 是 ul.direct 直接子级
        val nested = paras.first { it.text.contains("深层li") }
        assertNull(nested.spaceAboveEm)              // 内层 ul 下的 li 不命中 '>' 直接子代
    }

    @Test
    fun `important声明剥离后正常解析`() {
        val html = "<style>sup { vertical-align: super!important; font-size: 0.75em }</style>" +
            "<p>注<sup>1</sup>尾</p>"
        val paras = extractHtml(html)
        val supRun = paras[0].runs.single { it.style != 0 }
        assertEquals(RunStyle.SUP, supRun.style and RunStyle.SUP)
    }

    @Test
    fun `important保留在值中时em解析失败被宽容忽略`() {
        assertEquals("2em", HtmlTextExtractor.parseDeclarations("text-indent: 2em !important")["text-indent"])
        assertEquals("super", HtmlTextExtractor.parseDeclarations("vertical-align:super!important")["vertical-align"])
    }

    @Test
    fun `排版属性提取_align_indent_margin`() {
        val l1 = HtmlTextExtractor.parseParaLayout(mapOf("text-align" to "center"))
        assertEquals(1, l1!!.align)
        val l2 = HtmlTextExtractor.parseParaLayout(mapOf("text-align" to "right", "text-indent" to "0"))
        assertEquals(2, l2!!.align)
        assertEquals(CssLen(0f), l2.indentCss)
        val l3 = HtmlTextExtractor.parseParaLayout(mapOf("margin-top" to "1.5em", "margin-bottom" to "2em"))
        assertEquals(CssLen(1.5f), l3!!.aboveEm)
        assertEquals(CssLen(2f), l3.belowEm)
        val l4 = HtmlTextExtractor.parseParaLayout(mapOf("margin" to "1em"))
        assertEquals(CssLen(1f), l4!!.aboveEm)
        assertEquals(CssLen(1f), l4.belowEm)
        // v18 起缩进全单位(em/px/%);px 折 em(20px/16);left 现为显式对齐 3
        assertEquals(CssLen(1.25f), HtmlTextExtractor.parseParaLayout(mapOf("text-indent" to "20px"))!!.indentCss)
        assertEquals(3, HtmlTextExtractor.parseParaLayout(mapOf("text-align" to "left"))!!.align)
    }

    @Test
    fun `style与class合并且style优先_子未设用父`() {
        val html = """
            <style>.poem { text-align: center; text-indent: 0 }</style>
            <div style="margin-top: 1em">
              <p class="poem">居中诗行</p>
              <p style="text-align: right">右对齐覆盖</p>
              <p>普通段继承 margin</p>
            </div>
        """.trimIndent()
        val paras = extractHtml(html)
        assertEquals(1, paras[0].align)
        assertEquals(CssLen(0f), paras[0].indentCss)
        assertEquals(2, paras[1].align)              // style 覆盖 class
        assertEquals(CssLen(1f), paras[2].spaceAboveEm)   // 继承容器的 margin-top
    }

    @Test
    fun `heading记录与h2拆章`() {
        val paras = extractHtml(
            "<h1>第一章</h1><p>开头内容</p><h2>第一节</h2><p>内容一</p><h2>第二节</h2><p>内容二</p>"
        )
        assertEquals(1, paras[0].heading)
        assertEquals(0, paras[1].heading)
        assertEquals(2, paras[2].heading)
        val sections = com.yukino.tool.module.reader.epub.EpubImporter.splitSections(paras)
        assertEquals(3, sections.size)
        assertTrue(sections[0].first)
        assertNull(sections[0].h2Text)
        assertEquals("第一节", sections[1].h2Text)
        assertEquals(1, sections.size - 1 - sections.count { it.first })  // 两个 h2 小节
        val all = sections.flatMap { it.paras }
        assertEquals(paras, all)   // 拆分不丢段落
    }

    @Test
    fun `无h2文档单章`() {
        val paras = extractHtml("<h1>标题</h1><p>正文</p><h3>小标题</h3><p>更多</p>")
        val sections = com.yukino.tool.module.reader.epub.EpubImporter.splitSections(paras)
        assertEquals(1, sections.size)
        assertTrue(sections[0].first)
    }

    @Test
    fun `四期排版属性章文件往返`() {
        val f = File.createTempFile("ch_v4", ".txt")
        val paras = listOf(
            Paragraph("居中诗", align = 1),
            Paragraph("缩进段", indentEm = 0f),
            Paragraph("标题段", heading = 2, spaceAboveEm = CssLen(1.5f), spaceBelowEm = CssLen(2f))
        )
        ChapterFileCodec.write(f, paras)
        val (read, _) = ChapterFileCodec.read(f)
        assertEquals(1, read[0].align)
        assertEquals(0f, read[1].indentEm)
        assertEquals(2, read[2].heading)
        assertEquals(CssLen(1.5f), read[2].spaceAboveEm)
        assertEquals(CssLen(2f), read[2].spaceBelowEm)
        assertTrue(!com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(f))
        f.delete()
    }


    // ---- 老书升级: 进度迁移 ----

    // 旧书 3 章: 偏移 0/100/300(各章长 99/199/... 含虚拟换行),total = 600
    private val oldChs = listOf(
        com.yukino.tool.module.reader.common.ChapterIndex("一", 0),
        com.yukino.tool.module.reader.common.ChapterIndex("二", 100),
        com.yukino.tool.module.reader.common.ChapterIndex("三", 300)
    )
    // 新书(投影变长)同 3 章: 各章长约放大 2 倍
    private val newChs = listOf(
        com.yukino.tool.module.reader.common.ChapterIndex("一", 0),
        com.yukino.tool.module.reader.common.ChapterIndex("二", 200),
        com.yukino.tool.module.reader.common.ChapterIndex("三", 600)
    )

    @Test
    fun `进度迁移_章内中点等比缩放`() {
        // 旧第二章内偏移 100(章长 199)→ 新第二章长 399 → 约偏移 200
        val m = com.yukino.tool.module.reader.epub.EpubImporter.migrateProgress(oldChs, 600, 200, newChs, 1200)
        assertEquals(200L + 100L * 399 / 199, m)
    }

    @Test
    fun `进度迁移_章首与书首不变`() {
        assertEquals(0L, com.yukino.tool.module.reader.epub.EpubImporter.migrateProgress(oldChs, 600, 0, newChs, 1200))
        assertEquals(200L, com.yukino.tool.module.reader.epub.EpubImporter.migrateProgress(oldChs, 600, 100, newChs, 1200))
    }

    @Test
    fun `进度迁移_书末落到新书末章内`() {
        // 旧末章内 299(章长 300)→ 新末章长 600 → 章内 598
        val m = com.yukino.tool.module.reader.epub.EpubImporter.migrateProgress(oldChs, 600, 599, newChs, 1200)
        assertEquals(1198L, m)
    }

    @Test
    fun `进度迁移_新章数变少时clamp末章`() {
        val few = listOf(
            com.yukino.tool.module.reader.common.ChapterIndex("一", 0),
            com.yukino.tool.module.reader.common.ChapterIndex("二", 500)
        )
        // 旧第三章内 50(章长 300)→ clamp 到新末章(500 起,章长 500)→ 章内 83
        val m = com.yukino.tool.module.reader.epub.EpubImporter.migrateProgress(oldChs, 600, 350, few, 1000)
        assertEquals(583L, m)
    }

    @Test
    fun `进度迁移_空表与零值防御`() {
        assertEquals(0L, com.yukino.tool.module.reader.epub.EpubImporter.migrateProgress(emptyList(), 0, 100, newChs, 1200))
        assertEquals(0L, com.yukino.tool.module.reader.epub.EpubImporter.migrateProgress(oldChs, 600, 0, emptyList(), 0))
        assertEquals(0L, com.yukino.tool.module.reader.epub.EpubImporter.migrateProgress(oldChs, 0, 50, newChs, 1200))
    }

    @Test
    fun `升级检测_旧格式需升级_六期对象豁免`() {
        val legacy = File.createTempFile("legacy", ".txt")
        legacy.writeText("第一章 风起\n正文")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(legacy))
        val v2 = File.createTempFile("v2ch", ".txt")
        v2.writeText("""[{"t":"第一段"},{"t":"第二段"}]""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v2))   // 二期缺锚点/脚注
        val v4 = File.createTempFile("v4ch", ".txt")
        v4.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":4}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v4))   // 四期缺行内图片
        val v5 = File.createTempFile("v5ch", ".txt")
        v5.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":5}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v5))   // 五期缺外部CSS/float识别
        val v6 = File.createTempFile("v6ch", ".txt")
        v6.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":6}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v6))   // 六期缺七期选择器/ruby识别
        val v7 = File.createTempFile("v7ch", ".txt")
        v7.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":7}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v7))   // 七期批次一缺盒样式
        val v8 = File.createTempFile("v8ch", ".txt")
        v8.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":8}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v8))   // 批次二缺对象化 runs
        val v9 = File.createTempFile("v9ch", ".txt")
        v9.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":9}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v9))   // 批次三缺表格真渲染
        val v11 = File.createTempFile("v11ch", ".txt")
        v11.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":11}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v11))   // 批次四缺装饰盒字段
        val v10b = File.createTempFile("v10ch", ".txt")
        v10b.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":10}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v10b))   // 批次四a 缺装饰盒
        val v12 = File.createTempFile("v12ch", ".txt")
        v12.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":12}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v12))   // 缺盒自身定位/列宽提示
        val v13 = File.createTempFile("v13ch", ".txt")
        v13.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":13}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v13))   // 七期盒嵌套聚合/解析语义修正, v13 旧缓存需重提取
        val v15 = File.createTempFile("v15ch", ".txt")
        v15.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":15}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v15))   // 盒padding段距去重/br段距归零/圆角语义, v15 旧缓存需重提取
        val v16 = File.createTempFile("v16ch", ".txt")
        v16.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":16}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v16))
        val v17 = File.createTempFile("v17ch", ".txt")
        v17.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":17}""")
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v17))   // v19 混合渲染(装饰表格不再剥除), v17 旧缓存需重提取
        val v19 = File.createTempFile("v19ch", ".txt")
        v19.writeText("""{"p":[{"t":"第一段"}],"notes":{},"v":19}""")
        assertTrue(!com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v19))
        legacy.delete(); v2.delete(); v4.delete(); v5.delete(); v6.delete(); v7.delete(); v8.delete(); v9.delete(); v10b.delete(); v11.delete(); v12.delete(); v13.delete(); v15.delete(); v16.delete(); v17.delete(); v19.delete()
    }

    // ---- 老书升级: 章号映射(顺序保持的标题匹配) ----

    private fun ch(title: String, start: Long) = com.yukino.tool.module.reader.common.ChapterIndex(title, start)

    @Test
    fun `章号映射_中间插图页使后续章序号偏移`() {
        // 旧 3 章;新版在第二、三章之间插入图片页"插图"
        val old = listOf(ch("一", 0), ch("二", 100), ch("三", 300))
        val new = listOf(ch("一", 0), ch("二", 100), ch("插图", 300), ch("三", 320))
        val map = com.yukino.tool.module.reader.epub.EpubImporter.mapChapters(old, new)
        assertEquals(listOf(0, 1, 3), map.toList())
    }

    @Test
    fun `章号映射_书首插图页`() {
        val old = listOf(ch("一", 0), ch("二", 100))
        val new = listOf(ch("插图", 0), ch("一", 2), ch("二", 100))
        val map = com.yukino.tool.module.reader.epub.EpubImporter.mapChapters(old, new)
        assertEquals(listOf(1, 2), map.toList())
    }

    @Test
    fun `章号映射_未匹配章在锚点间线性插值`() {
        // 旧章"乙"标题在新版缺失: 落在甲(0)与丙(2)之间 → 插值 1
        val old = listOf(ch("甲", 0), ch("乙", 100), ch("丙", 300))
        val new = listOf(ch("甲", 0), ch("插图", 100), ch("丙", 320))
        val map = com.yukino.tool.module.reader.epub.EpubImporter.mapChapters(old, new)
        assertEquals(listOf(0, 1, 2), map.toList())
    }

    @Test
    fun `章号映射_全部未匹配退回序号`() {
        val old = listOf(ch("甲", 0), ch("乙", 100))
        val new = listOf(ch("X", 0), ch("Y", 100), ch("Z", 300))
        val map = com.yukino.tool.module.reader.epub.EpubImporter.mapChapters(old, new)
        assertEquals(listOf(0, 1), map.toList())
    }

    @Test
    fun `进度迁移_章号经标题映射偏移`() {
        // 旧读在"三"章首(300);新版"三"被插图页推到 320 → 迁移后落在 320
        val old = listOf(ch("一", 0), ch("二", 100), ch("三", 300))
        val new = listOf(ch("一", 0), ch("二", 100), ch("插图", 300), ch("三", 320))
        val m = com.yukino.tool.module.reader.epub.EpubImporter.migrateProgress(old, 600, 300, new, 1200)
        assertEquals(320L, m)
    }

    // ---- 三期: 锚点与脚注 ----

    @Test
    fun `元素id记为段落anchor_容器id指向首段`() {
        val paras = extractHtml("<div id=\"sec1\"><p>第一段</p><p>第二段</p></div><p id=\"p3\">第三段</p>")
        assertEquals("sec1", paras[0].anchor)
        assertNull(paras[1].anchor)   // 容器 id 只指向首段
        assertEquals("p3", paras[2].anchor)
    }

    @Test
    fun `noteref角标保留进投影带上标与脚注锚点`() {
        val r = extractFull(
            "<p>正文一段<sup><a epub:type=\"noteref\" href=\"#fn1\">[1]</a></sup>继续。</p>" +
                "<aside id=\"fn1\" epub:type=\"footnote\">这是第一条脚注的内容。</aside>"
        )
        assertEquals(1, r.paragraphs.size)
        val p = r.paragraphs[0]
        assertTrue(p.text.contains("[1]"))
        assertEquals(1, p.notes.size)
        assertEquals("[1]".length, p.notes[0].end - p.notes[0].start)
        assertEquals("fn1", p.notes[0].noteId)
        // 角标区间套上标 Run
        val supRun = p.runs.firstOrNull { it.start == p.notes[0].start && it.end == p.notes[0].end }
        assertEquals(com.yukino.tool.module.reader.common.RunStyle.SUP, supRun?.style)
        // 脚注内容入表且不进正文流
        assertEquals(mapOf("fn1" to "这是第一条脚注的内容。"), r.footnotes)
        assertEquals(1, r.paragraphs.size)
    }

    @Test
    fun `class标记的脚注容器被识别`() {
        val r = extractFull(
            "<p>正文<a class=\"noteref\" href=\"#n1\">1</a>。</p>" +
                "<div class=\"footnote\" id=\"n1\"><p>类标记脚注</p></div>"
        )
        assertEquals("类标记脚注", r.footnotes["n1"])
        assertEquals(1, r.paragraphs[0].notes.size)
    }

    @Test
    fun `跨文档noteref降级为普通文本`() {
        val r = extractFull(
            "<p>正文<a epub:type=\"noteref\" href=\"notes.xhtml#n9\">[9]</a>。</p>"
        )
        assertEquals(0, r.paragraphs[0].notes.size)
        assertTrue(r.footnotes.isEmpty())
        assertTrue(r.paragraphs[0].text.contains("[9]"))
    }

    @Test
    fun `普通内链不误判为角标`() {
        val r = extractFull("<p>见<a href=\"#other\">第2节</a>。</p>")
        assertEquals(0, r.paragraphs[0].notes.size)
        assertTrue(r.paragraphs[0].text.contains("第2节"))
    }

    @Test
    fun `三期章文件含锚点脚注读写`() {
        val f = File.createTempFile("ch_v3", ".txt")
        val paras = listOf(
            Paragraph("带锚段落", anchor = "sec-1"),
            Paragraph("角标段[1]", notes = listOf(
                com.yukino.tool.module.reader.common.NoteAnchor(3, 6, "fn1")
            ))
        )
        ChapterFileCodec.write(f, paras, mapOf("fn1" to "脚注内容"))
        val (read, notes) = ChapterFileCodec.read(f)
        assertEquals("sec-1", read[0].anchor)
        assertEquals(1, read[1].notes.size)
        assertEquals("fn1", read[1].notes[0].noteId)
        assertEquals(mapOf("fn1" to "脚注内容"), notes)
        f.delete()
    }

    @Test
    fun `anchorOffset按段落定位`() {
        val book = com.yukino.tool.module.reader.common.ReaderBook(
            id = "t", title = "t", sourceUri = "", cachePath = "", encoding = "",
            totalChars = 0, chapters = emptyList(), addedAt = 0, lastReadAt = 0
        )
        val content = com.yukino.tool.module.reader.epub.EpubBookContent(book, java.io.File("."))
        // 章内段落: [0,3)锚a1 / [4,7) / [8,11)锚a3 → 偏移 0 / 8(投影累计逻辑,与
        // EpubBookContent.anchorOffset 同构)
        val paras = listOf(
            Paragraph("AAA", anchor = "a1"), Paragraph("BBB"), Paragraph("CCC", anchor = "a3")
        )
        var off = 0L
        val found = LinkedHashMap<String, Long>()
        for (p in paras) {
            if (p.anchor != null) found[p.anchor!!] = off
            off += p.text.length + 1L
        }
        assertEquals(0L, found["a1"])
        assertEquals(8L, found["a3"])
        assertNull(content.anchorOffset(99, "a1"))   // 章越界
    }

    // ---- 七期批次二: 长度/边距/定宽/显式对齐/行距/颜色/边框/盒组 ----

    @Test
    fun `CssLen解析_em_px与百分比`() {
        fun f(v: String) = CssLen.parse(v)!!
        assertEquals(CssLen(2f), f("2em"))
        assertEquals(CssLen(0f), f("0"))
        assertEquals(CssLen(0.75f), f("12px"))
        assertEquals(CssLen(10f, pct = true), f("10%"))
        assertNull(CssLen.parse("1.5rem"))
        assertNull(CssLen.parse("auto"))
        // px 换算: em×字号, %×可用宽
        assertEquals(38f, f("2em").px(19f, 400f), 0.001f)
        assertEquals(40f, f("10%").px(19f, 400f), 0.001f)
    }

    @Test
    fun `左右边距与padding折算进段布局`() {
        val l1 = HtmlTextExtractor.parseParaLayout(
            mapOf("margin-left" to "2em", "margin-right" to "10%", "width" to "24em")
        )
        assertEquals(CssLen(2f), l1!!.leftEm)
        assertEquals(CssLen(10f, pct = true), l1.rightEm)
        assertEquals(CssLen(24f), l1.widthEm)
        val l2 = HtmlTextExtractor.parseParaLayout(
            mapOf("padding-top" to "0.3em", "padding-left" to "1em", "padding-right" to "6px")
        )
        assertEquals(CssLen(0.3f), l2!!.aboveEm)
        assertEquals(CssLen(1f), l2.leftEm)
        assertEquals(CssLen(6f / 16f), l2.rightEm)
        // margin 简写四值取上下 + 左右;2em 22em 定宽段
        val l3 = HtmlTextExtractor.parseParaLayout(mapOf("margin" to "0.2em 0 0 0.3em"))
        assertEquals(CssLen(0.2f), l3!!.aboveEm)
        assertEquals(CssLen(0f), l3.belowEm)
    }

    @Test
    fun `margin简写auto定位语义`() {
        // 双 auto = 居中
        assertEquals(1, HtmlTextExtractor.parseParaLayout(mapOf("margin" to "3% auto", "width" to "24em"))!!.widthAlign)
        // 左 auto = 贴右(t-box3 案例: 四值 -1.7em 0.3em 0 auto, 左位 auto 把盒推到右缘)
        assertEquals(2, HtmlTextExtractor.parseParaLayout(mapOf("margin" to "-1.7em 0.3em 0 auto", "width" to "1em"))!!.widthAlign)
        // 右 auto = 贴左(四值 0 auto 0 2em: 上0 右auto 下0 左2em)
        assertEquals(3, HtmlTextExtractor.parseParaLayout(mapOf("margin" to "0 auto 0 2em", "width" to "10em"))!!.widthAlign)
        // 分边属性 auto 同义
        assertEquals(2, HtmlTextExtractor.parseParaLayout(mapOf("margin-left" to "auto", "width" to "10em"))!!.widthAlign)
    }

    @Test
    fun `显式left与justify对齐`() {
        assertEquals(3, HtmlTextExtractor.parseParaLayout(mapOf("text-align" to "left"))!!.align)
        assertEquals(4, HtmlTextExtractor.parseParaLayout(mapOf("text-align" to "justify"))!!.align)
    }

    @Test
    fun `行距倍率解析`() {
        assertEquals(1.3f, HtmlTextExtractor.lineHeightVal("1.3em")!!)
        assertEquals(1.2f, HtmlTextExtractor.lineHeightVal("1.2")!!)
        assertEquals(1.2f, HtmlTextExtractor.lineHeightVal("120%")!!)
        assertEquals(0f, HtmlTextExtractor.lineHeightVal("0%")!!)
        assertNull(HtmlTextExtractor.lineHeightVal("normal"))
    }

    @Test
    fun `颜色解析_hex_rgb与命名`() {
        assertEquals(0xFF6B8E23L, HtmlTextExtractor.parseColor("#6b8e23"))
        assertEquals(0xFFFF0000L, HtmlTextExtractor.parseColor("#FF0000"))
        assertEquals(0xFF331122L, HtmlTextExtractor.parseColor("#312"))
        assertEquals(0xFF331122L, HtmlTextExtractor.parseColor("rgb(51, 17, 34)"))
        assertEquals(0x80FFFFFFL, HtmlTextExtractor.parseColor("rgba(255, 255, 255, 0.5)"))
        assertEquals(0xFFFFFFFFL, HtmlTextExtractor.parseColor("white"))
        assertEquals(0xFF000000L, HtmlTextExtractor.parseColor("black"))
        assertNull(HtmlTextExtractor.parseColor("notacolor"))
    }

    @Test
    fun `border归并_简写与分边覆盖`() {
        val edges = HtmlTextExtractor.parseEdges(
            mapOf(
                "border" to "2px solid #000000",
                "border-left" to "thick #FF0080 solid",
                "border-right-width" to "0.5em",
                "border-style" to "dotted solid dotted none"
            )
        )
        // border-style 多值展开后: 上=dotted 下=dotted 右=solid 左=none;
        // 分边 border-left 再覆盖(粗+彩+solid)
        assertEquals(2, edges[0].style)                       // 上 dotted
        assertEquals(1, edges[1].style)                       // 右 solid(border-style 展开)
        assertEquals(0.5f, edges[1].widthEm)                  // 右宽 0.5em
        assertEquals(2, edges[2].style)                       // 下 dotted
        assertEquals(1, edges[3].style)                       // 左 solid(分边覆盖)
        assertEquals(0xFF0080L, edges[3].color and 0xFFFFFF)
        assertEquals(5f / 16f, edges[3].widthEm)              // thick
        // 全 none 的 border:0
        val none = HtmlTextExtractor.parseEdges(mapOf("border" to "0"))
        assertTrue(none.all { it.style == 0 })
    }

    @Test
    fun `盒样式生成与盒组归属`() {
        val html = """
            <style>.ibox { border-left: solid 16px #F768A4; background-color: #ffffff;
            padding: 1px; border-radius: 6px; box-shadow: 2px 2px 3px #000 }
            .inner p { margin: 0 }</style>
            <div class="ibox"><p>盒内第一段</p><p>盒内第二段</p></div>
            <p>盒外段落</p>
        """.trimIndent()
        val paras = extractHtml(html)
        val box = paras[0].boxStyle
        assertTrue(box != null)
        assertEquals(0xFFFFFFFFL, box!!.bg)
        assertEquals(1, box.edges.count { it.widthEm > 0f && it.style > 0 })
        assertEquals(16f / 16f, box.edges[3].widthEm)   // 左边 16px
        assertEquals(0xFFF768A4L, box.edges[3].color)
        assertEquals(CssLen(6f / 16f), box.radius)
        assertTrue(box.shadow)
        assertTrue(paras[1].boxStyle === paras[0].boxStyle)   // 同一盒实例(聚合绘制)
        assertNull(paras[2].boxStyle)
        // 盒 padding 折算段左缩进(padding: 1px = 1/16 em)
        assertEquals(CssLen(1f / 16f), paras[0].marginLeftEm)
    }

    @Test
    fun `七期段落字段章文件往返`() {
        val f = File.createTempFile("ch_v8", ".txt")
        val box = com.yukino.tool.module.reader.common.BoxStyle(
            bg = 0xFFFFFFFFL, radius = CssLen(6f / 16f), shadow = true,
            edges = listOf(
                com.yukino.tool.module.reader.common.EdgeStyle(0f, 0, 0xFF000000L),
                com.yukino.tool.module.reader.common.EdgeStyle(0f, 0, 0xFF000000L),
                com.yukino.tool.module.reader.common.EdgeStyle(0f, 0, 0xFF000000L),
                com.yukino.tool.module.reader.common.EdgeStyle(1f, 1, 0xFFF768A4L)
            )
        )
        val paras = listOf(
            Paragraph("定宽段", widthEm = CssLen(24f), widthAlign = 1, marginLeftEm = CssLen(2f),
                marginRightEm = CssLen(3f), lineSpacingMult = 1.2f,
                spaceAboveEm = CssLen(10f, pct = true), boxStyle = box)
        )
        ChapterFileCodec.write(f, paras)
        val (read, _) = ChapterFileCodec.read(f)
        val p = read[0]
        assertEquals(CssLen(24f), p.widthEm)
        assertEquals(1, p.widthAlign)
        assertEquals(CssLen(2f), p.marginLeftEm)
        assertEquals(CssLen(3f), p.marginRightEm)
        assertEquals(1.2f, p.lineSpacingMult)
        assertEquals(CssLen(10f, pct = true), p.spaceAboveEm)
        assertEquals(box, p.boxStyle)
        assertTrue(!com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(f))
        f.delete()
    }

    @Test
    fun `段落度量_定宽居中与右缩进判定`() {
        val typo = com.yukino.tool.module.reader.common.Typography.resolve(
            2f, com.yukino.tool.module.reader.common.ReaderSettings(),
            800, 1200
        )
        // margin auto + width → 居中: 左右留白对称,需独立断行(定宽取版心 1/3)
        val wEm = typo.textWidth / 3f / typo.fontPx
        val pm = BookPager.paraMetrics(
            Paragraph("x", widthEm = CssLen(wEm), widthAlign = 1), typo
        )
        assertEquals(typo.textWidth.toFloat() / 2f - wEm * typo.fontPx / 2f, pm.mlPx, 0.01f)
        assertTrue(pm.needsOverride)
        // 左 auto(贴右): ml = 版心 - 内容宽
        val pmR = BookPager.paraMetrics(
            Paragraph("x", widthEm = CssLen(wEm), widthAlign = 2), typo
        )
        assertEquals(typo.textWidth - wEm * typo.fontPx, pmR.mlPx, 0.01f)
        assertEquals(0f, pmR.mrPx)
        // margin-left only → 无右缩进,主 layout 断行(leading margin 机制)
        val pm2 = BookPager.paraMetrics(Paragraph("x", marginLeftEm = CssLen(2f)), typo)
        assertEquals(2f * typo.fontPx, pm2.mlPx, 0.01f)
        assertEquals(0f, pm2.mrPx)
        assertTrue(!pm2.needsOverride)
        // 无书内布局 → 全宽
        val pm3 = BookPager.paraMetrics(Paragraph("x"), typo)
        assertEquals(typo.textWidth.toFloat(), pm3.availWidth, 0.01f)
        assertTrue(!pm3.needsOverride)
    }

    // ---- 七期批次三: 字号/颜色/阴影/字体/图片定宽 ----

    @Test
    fun `fontSize与color与shadow提取`() {
        assertEquals(0.75f, HtmlTextExtractor.fontSizeEm("0.75em")!!)
        assertEquals(0.75f, HtmlTextExtractor.fontSizeEm("12px")!!)
        assertEquals(0.8f, HtmlTextExtractor.fontSizeEm("80%")!!)
        assertEquals(1.5f, HtmlTextExtractor.fontSizeEm("x-large")!!)
        assertEquals(2f, HtmlTextExtractor.fontSizeEm("xx-large")!!)
        assertNull(HtmlTextExtractor.fontSizeEm("larger"))
        val ctx = HtmlTextExtractor.runDecoFromProps(
            mapOf("font-size" to "1.1em", "color" to "#ff0000", "text-shadow" to "1px 1px 2px #000")
        )
        assertEquals(1.1f, ctx.sizeEm!!)
        assertEquals(0xFFFF0000L, ctx.color)
        assertTrue(ctx.shadow)
    }

    @Test
    fun `class规则字号颜色生效且回落继承`() {
        val html = """
            <style>.em08 { font-size: 0.8em } .co1 { color: #FF0000 }
            .co4 { color: #00CACA; text-shadow: 1px 1px 2px #000; font-weight: bold }</style>
            <p class="em08">小字整段</p>
            <p>普通<span class="co1">红字</span>后缀</p>
            <p class="co4">彩字阴影加粗</p>
        """.trimIndent()
        val paras = extractHtml(html)
        assertTrue(paras[0].runs.all { it.sizeEm == 0.8f })
        // 内层 span 的 color 覆盖,前后缀无 color
        assertEquals(0xFFFF0000L, paras[1].runs.first { it.color != null }.color)
        assertTrue(paras[1].runs.any { it.color == null })
        val c4 = paras[2].runs.first()
        assertEquals(0xFF00CACAL, c4.color)
        assertTrue(c4.shadow)
        assertEquals(RunStyle.BOLD, c4.style and RunStyle.BOLD)
    }

    @Test
    fun `fontFamily解析与fontface收集`() {
        assertEquals("title", HtmlTextExtractor.fontFamilyName("title"))
        assertEquals("tt1", HtmlTextExtractor.fontFamilyName("\"tt1\", serif"))
        val faces = HtmlTextExtractor.parseFontFaces(
            "@font-face { font-family: \"title\";\n src: url(../Fonts/title.ttf); }\n" +
                "@font-face { font-family: tt2; src:url(\"../Fonts/tt2.ttf\"); }"
        )
        assertEquals("../Fonts/title.ttf", faces["title"])
        assertEquals("../Fonts/tt2.ttf", faces["tt2"])
    }

    @Test
    fun `外部CSS的字体与字号随class生效`() {
        val dir = File.createTempFile("cssdir3", "").let { it.delete(); it.mkdirs(); it }
        val fonts = File(dir, "Fonts").mkdirs(); assertTrue(fonts || File(dir, "Fonts").isDirectory)
        val css = File(dir, "style.css")
        css.writeText(
            "@font-face { font-family: title; src: url(Fonts/title.ttf); }\n" +
                ".title { font-family: title; font-size: 1.5em; color: #8118D3 }"
        )
        val html = File(dir, "ch.xhtml")
        html.writeText("<html><head><link href=\"style.css\" rel=\"stylesheet\"/></head>" +
            "<body><p class=\"title\">标题字</p></body></html>")
        val r = HtmlTextExtractor.extract(html, "")
        val run = r.paragraphs[0].runs.first()
        assertEquals(1.5f, run.sizeEm)
        assertEquals(0xFF8118D3L, run.color)
        assertEquals(0, run.fontId)   // family 登记 fonts 表首项
        assertEquals("title", r.fonts.keys.first())
        html.delete(); css.delete(); File(dir, "Fonts").delete(); dir.delete()
    }

    @Test
    fun `九期章文件对象化runs往返`() {
        val f = File.createTempFile("ch_v9", ".txt")
        val paras = listOf(
            Paragraph("大小颜色", listOf(
                Run(0, 1, 0, sizeEm = 0.75f),
                Run(1, 2, RunStyle.BOLD, color = 0xFF00CACAL, shadow = true, fontId = 1)
            ))
        )
        ChapterFileCodec.write(f, paras, fonts = mapOf("title" to "Fonts/title.ttf", "tt1" to "Fonts/tt1.ttf"))
        val read = ChapterFileCodec.read(f)
        val runs = read.paragraphs[0].runs
        assertEquals(0.75f, runs[0].sizeEm)
        assertNull(runs[0].color)
        assertEquals(RunStyle.BOLD, runs[1].style and RunStyle.BOLD)
        assertEquals(0xFF00CACAL, runs[1].color)
        assertTrue(runs[1].shadow)
        assertEquals(1, runs[1].fontId)
        assertEquals("tt1", read.fonts[1])
        assertEquals("Fonts/title.ttf", read.fontPaths["title"])
        assertTrue(!com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(f))
        f.delete()
    }

    @Test
    fun `v8扁平runs章文件兼容读`() {
        val f = File.createTempFile("ch_v8compat", ".txt")
        f.writeText("""{"p":[{"t":"AB","r":[0,1,1,1,2,0]}],"notes":{},"v":8}""")
        val read = ChapterFileCodec.read(f)
        assertEquals(listOf(Run(0, 1, 1), Run(1, 2, 0)), read.paragraphs[0].runs)
        assertTrue(com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(f))
        f.delete()
    }

    // ---- 批次四 b/c/d/e: float 环绕/固定高/旋转/break-all ----

    @Test
    fun `浮动盒固定高与旋转提取`() {
        val html = """
            <style>.t-box2 { width: 3.5em; height: 3.5em; border-radius: 100px;
            border: solid 5px #000 }
            .fr { float: right }
            .rotate1 { transform: rotate(-5deg) }
            .da { word-break: break-all }</style>
            <div class="t-box2 fr"><p>圆盒</p></div>
            <div class="rotate1">斜盒</div>
            <p class="da">breakall段</p>
        """.trimIndent()
        val paras = extractHtml(html)
        val fr = paras[0]
        assertEquals(1, fr.floatSide)
        val box = fr.boxStyle!!
        assertEquals(com.yukino.tool.module.reader.common.CssLen(3.5f), box.heightCss)
        assertTrue(paras[1].boxStyle!!.rotateDeg == -5f)
        assertTrue(paras[2].breakAll)
    }

    @Test
    fun `无宽高的float保持降级不标记环绕`() {
        val paras = extractHtml("<style>.fr2 { float: right }</style><div class=\"fr2\"><p>无定宽浮块</p></div>")
        assertEquals(1, paras[0].floatSide)
        assertNull(paras[0].boxStyle?.heightCss)
    }

    @Test
    fun `批次四字段章文件往返`() {
        val f = File.createTempFile("ch_v12", ".txt")
        val box = com.yukino.tool.module.reader.common.BoxStyle(
            heightCss = com.yukino.tool.module.reader.common.CssLen(3.5f), rotateDeg = -5f
        )
        val paras = listOf(
            Paragraph("浮盒", floatSide = 1, widthEm = com.yukino.tool.module.reader.common.CssLen(3.5f),
                boxStyle = box, breakAll = false),
            Paragraph("断词", breakAll = true)
        )
        ChapterFileCodec.write(f, paras)
        val read = ChapterFileCodec.read(f)
        assertEquals(1, read.paragraphs[0].floatSide)
        assertEquals(com.yukino.tool.module.reader.common.CssLen(3.5f), read.paragraphs[0].boxStyle!!.heightCss)
        assertEquals(-5f, read.paragraphs[0].boxStyle!!.rotateDeg!!)
        assertTrue(read.paragraphs[1].breakAll)
        assertTrue(!com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(f))
        f.delete()
    }

    // ---- 选择器完整化: #id / [attr] / * / 多类 ----

    @Test
    fun `id选择器命中且specificity覆盖类与元素`() {
        val paras = extractHtml(
            "<style>#intro{color:#123456}.intro{color:#654321}p{color:#111111}</style>" +
                "<p class=\"intro\" id=\"intro\">ab</p><p class=\"intro\">cd</p><p>ef</p>"
        )
        assertEquals(0xFF123456L, paras[0].runs[0].color)
        assertEquals(0xFF654321L, paras[1].runs[0].color)
        assertEquals(0xFF111111L, paras[2].runs[0].color)
    }

    @Test
    fun `属性选择器各操作符`() {
        val html = "<style>" +
            "p[data-x]{color:#010101}" +          // 存在
            "td[colspan=\"2\"]{color:#020202}" +  // =
            "p[class~=\"bb\"]{color:#030303}" +   // ~= 词列表
            "a[lang|=\"zh\"]{color:#040404}" +    // |= 连字号前缀
            "a[href^=\"https\"]{color:#050505}" + // ^=
            "a[href\$=\".png\"]{color:#060606}" + // $=
            "p[title*=\"ell\"]{color:#070707}" +  // *= 子串
            "</style>" +
            "<p data-x=\"1\" class=\"aa bb cc\" title=\"hello\">词</p>" +
            "<a href=\"https://x/y.png\" lang=\"zh-CN\">链</a>" +
            "<table><tr><td colspan=\"2\">格</td></tr></table>"
        val paras = extractHtml(html)
        // p: 存在 + ~= + *= 全命中 → 最高优先为源序末条(#070707)
        assertEquals(0xFF070707L, paras[0].runs[0].color)
        // a: |= 与 ^= $= 命中 → 源序末条 #060606
        assertEquals(0xFF060606L, paras[1].runs[0].color)
        // td = 命中
        assertEquals(0xFF020202L, paras[2].table!!.cells[0].runs[0].color)
    }

    @Test
    fun `通配与多类选择器`() {
        val paras = extractHtml(
            "<style>*{color:#0a0a0a}.a.b{color:#0b0b0b}.only{color:#0c0c0c}</style>" +
                "<p class=\"a b\">ab</p><p class=\"a\">cd</p><p>ef</p><div class=\"only\">gh</div>"
        )
        assertEquals(0xFF0B0B0BL, paras[0].runs[0].color)   // .a.b(20) > *(0)
        assertEquals(0xFF0A0A0AL, paras[1].runs[0].color)   // 仅 *
        assertEquals(0xFF0A0A0AL, paras[2].runs[0].color)
        assertEquals(0xFF0C0C0CL, paras[3].runs[0].color)
    }

    @Test
    fun `子代选择器带id与属性仍解析`() {
        val paras = extractHtml(
            "<style>div > p#k{color:#0d0d0d}</style><div><p id=\"k\">内</p></div><p id=\"k\">外</p>"
        )
        assertEquals(0xFF0D0D0DL, paras[0].runs[0].color)   // div 子代命中
        // body 直下的 p#k 无 div 父,不命中(无有效样式 runs 退化为空)
        assertTrue(paras[1].runs.isEmpty() || paras[1].runs[0].color == null)
    }

    // ---- 颜色: rgb/rgba(既有) + 命名色全表 + border 简写函数色含空格 ----

    @Test
    fun `命名色全表抽查`() {
        assertEquals(0xFF663399L, HtmlTextExtractor.parseColor("rebeccapurple"))
        assertEquals(0xFF2F4F4FL, HtmlTextExtractor.parseColor("darkslategray"))
        assertEquals(0xFF2F4F4FL, HtmlTextExtractor.parseColor("darkslategrey"))
        assertEquals(0xFFFF69B4L, HtmlTextExtractor.parseColor("hotpink"))
        assertEquals(0xFFFFFFE0L, HtmlTextExtractor.parseColor("lightyellow"))
        assertEquals(0xFF7FFFD4L, HtmlTextExtractor.parseColor("aquamarine"))
    }

    @Test
    fun `border简写带空格rgb颜色不被拆坏`() {
        val edges = HtmlTextExtractor.parseEdges(HtmlTextExtractor.parseDeclarations("border: 1px solid rgb(0, 0, 0)"))
        assertEquals(0xFF000000L, edges[0].color)
        assertEquals(1f / 16f, edges[0].widthEm, 1e-5f)
        assertEquals(1, edges[0].style)
    }

    @Test
    fun `样式规则里的rgb颜色经选择器生效`() {
        val paras = extractHtml("<style>.c1{color:rgb(51, 17, 34);border-bottom:2px solid rgba(0, 0, 0, 0.4)}</style><p class=\"c1\">ab</p>")
        assertEquals(0xFF331122L, paras[0].runs[0].color)
        assertEquals(0x66000000L, paras[0].boxStyle!!.edges[2].color)   // bottom = 四边下标 2
    }

    // ---- 首行缩进 px/% ----

    @Test
    fun `首行缩进支持px与百分比与零`() {
        val p1 = extractHtml("<p style=\"text-indent:32px\">ab</p>")[0]
        assertEquals(CssLen(2f, false), p1.indentCss)
        val p2 = extractHtml("<p style=\"text-indent:10%\">ab</p>")[0]
        assertEquals(CssLen(10f, true), p2.indentCss)
        val p3 = extractHtml("<p style=\"text-indent:0\">ab</p>")[0]
        assertEquals(CssLen(0f, false), p3.indentCss)
        val p4 = extractHtml("<style>p{text-indent:1.5em}</style><p>ab</p>")[0]
        assertEquals(CssLen(1.5f, false), p4.indentCss)
    }

    @Test
    fun `缩进字段章文件往返且v19不再升级`() {
        val f = File.createTempFile("ch_v19", ".txt")
        val td = com.yukino.tool.module.reader.common.TableData(
            rows = 1, cols = 1,
            cells = listOf(com.yukino.tool.module.reader.common.TableCell(0, 0, text = "格", imgRef = "d/i.png"))
        )
        val paras = listOf(
            Paragraph("缩进段", indentCss = CssLen(2f, false)),
            Paragraph(HtmlTextExtractor.IMAGE_PLACEHOLDER, kind = ParaKind.TABLE, table = td)
        )
        ChapterFileCodec.write(f, paras)
        val read = ChapterFileCodec.read(f)
        assertEquals(CssLen(2f, false), read.paragraphs[0].indentCss)
        assertEquals("d/i.png", read.paragraphs[1].table!!.cells[0].imgRef)
        assertTrue(!ChapterFileCodec.needsUpgrade(f))
        // 降版本号模拟旧缓存: 可读但触发重提取
        f.writeText(f.readText().replace("\"v\": 19", "\"v\": 17").replace("\"v\":19", "\"v\":17"))
        assertTrue(ChapterFileCodec.needsUpgrade(f))
        f.delete()
    }

    // ---- larger/smaller 相对字号 ----

    @Test
    fun `larger与smaller相对父字号复合`() {
        val paras = extractHtml(
            "<div style=\"font-size:1.5em\"><p>先<span style=\"font-size:larger\">大</span>" +
                "<span style=\"font-size:smaller\">小</span></p></div>"
        )
        val runs = paras[0].runs
        assertEquals(1.8f, runs.first { "大" == paras[0].text.substring(it.start, it.end) }.sizeEm!!, 1e-4f)
        assertEquals(1.25f, runs.first { "小" == paras[0].text.substring(it.start, it.end) }.sizeEm!!, 1e-4f)
        assertEquals(1.5f, runs.first { "先" == paras[0].text.substring(it.start, it.end) }.sizeEm!!, 1e-4f)
    }

    // ---- data URI 图片 ----

    @Test
    fun `dataURI图片经sink落盘接入ref`() {
        val seen = ArrayList<Pair<String, String>>()
        val paras = HtmlTextExtractor.extract(
            Jsoup.parseBodyFragment("<p><img src=\"data:image/png;base64,iVBORw0KGgo=\"></p>").body(),
            "", null, null,
            dataUriSink = { mime, b64 -> seen += mime to b64; "datauri/x.png" }
        ).paragraphs
        assertEquals(listOf("image/png" to "iVBORw0KGgo="), seen)
        assertEquals("datauri/x.png", paras[0].imageRef)
    }

    @Test
    fun `dataURI无sink或拒收仍忽略`() {
        val dropped = extractHtml("<p><img src=\"data:image/png;base64,iVBORw0KGgo=\"></p>")
        assertTrue(dropped.isEmpty() || !dropped[0].isImage)
        val rejected = HtmlTextExtractor.extract(
            Jsoup.parseBodyFragment("<p><img src=\"data:image/png;base64,iVBORw0KGgo=\"></p>").body(),
            "", null, null,
            dataUriSink = { _, _ -> "" }
        ).paragraphs
        assertTrue(rejected.isEmpty() || !rejected[0].isImage)
        // 非 base64 形态不进 sink
        val notB64 = ArrayList<Pair<String, String>>()
        HtmlTextExtractor.extract(
            Jsoup.parseBodyFragment("<p><img src=\"data:image/svg+xml,%3Csvg%3E\"></p>").body(),
            "", null, null,
            dataUriSink = { m, b -> notB64 += m to b; "x" }
        )
        assertTrue(notB64.isEmpty())
    }

    // ---- 表格格内图片 ----

    @Test
    fun `表格格内图片提取与纯图格产出`() {
        val paras = extractHtml("<table><tr><td><img src=\"pic/a.png\"></td><td>文字</td></tr></table>")
        val td = paras[0].table!!
        assertEquals(2, td.cells.size)
        assertEquals("pic/a.png", td.cells[0].imgRef)
        assertEquals("", td.cells[0].text)
        assertNull(td.cells[1].imgRef)
        assertEquals("文字", td.cells[1].text)
    }

    @Test
    fun `格内图片尺寸等比钳高与占位`() {
        val ci = BookPager.cellImgSize(200, 100, 100f, 300f, 20f)
        assertEquals(100f, ci.width, 0.01f)
        assertEquals(50f, ci.height, 0.01f)
        val ci2 = BookPager.cellImgSize(100, 1000, 100f, 300f, 20f)
        assertEquals(300f, ci2.height, 0.01f)
        assertEquals(30f, ci2.width, 0.01f)
        val ci3 = BookPager.cellImgSize(0, 0, 100f, 300f, 20f)
        assertEquals(100f, ci3.width, 0.01f)
        assertEquals(40f, ci3.height, 0.01f)
    }

    @Test
    fun `格内图片占位高计入表格行高`() {
        val typo = com.yukino.tool.module.reader.common.Typography.resolve(
            2f, com.yukino.tool.module.reader.common.ReaderSettings(), 800, 1200
        )
        val td = com.yukino.tool.module.reader.common.TableData(
            rows = 1, cols = 1,
            cells = listOf(com.yukino.tool.module.reader.common.TableCell(0, 0, text = "x", imgRef = "p.png"))
        )
        // imageBounds 未注入 → 占位高 2*lineH;行高 ≥ 文本一行 + 图片两行
        val tl2 = BookPager.layoutTable(
            td, typo.textWidth.toFloat(), typo, { s -> s.length * 10f }
        )
        assertTrue(tl2.heights[0] >= 3 * tl2.lineH + tl2.padV * 2 - 1f)
        val cb = tl2.cells[0]
        assertEquals(2f * tl2.lineH, cb.img!!.height, 0.01f)
        assertEquals(typo.textWidth.toFloat() - 2 * tl2.padH, cb.img!!.width, 1f)
    }
}
