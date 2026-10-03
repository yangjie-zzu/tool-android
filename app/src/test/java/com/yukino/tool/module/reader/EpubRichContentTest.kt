package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.ChapterLines
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
    fun `简单表格逐行管道连接`() {
        val paras = extractHtml("<table><tr><td>a</td><td>b</td></tr><tr><td>1</td><td>2</td></tr></table>")
        assertEquals(listOf("a | b", "1 | 2"), paras.map { it.text })
    }

    @Test
    fun `跨行列复杂表格出占位段`() {
        val paras = extractHtml(
            "<table><tr><td rowspan=\"2\">a</td><td>b</td></tr><tr><td>c</td></tr></table>"
        )
        assertEquals(1, paras.size)
        assertTrue(paras[0].text.contains("表格内容"))
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
        assertNull(HtmlTextExtractor.parseParaLayout(mapOf("float" to "left")))
        // float 与显式对齐同设时,显式对齐优先
        val r2 = HtmlTextExtractor.parseParaLayout(mapOf("float" to "right", "text-align" to "center"))
        assertEquals(1, r2!!.align)
        assertTrue(r2.floatRight)
    }

    @Test
    fun `负margin提取`() {
        // 四值简写 margin: top right bottom left → 上=-0.2 下=-0.1
        val l = HtmlTextExtractor.parseParaLayout(mapOf("margin" to "-0.2em 0 -0.1em 0"))
        assertEquals(-0.2f, l!!.aboveEm)
        assertEquals(-0.1f, l.belowEm)
        val l2 = HtmlTextExtractor.parseParaLayout(mapOf("margin-top" to "-1em", "margin-bottom" to "0.3em"))
        assertEquals(-1f, l2!!.aboveEm)
        assertEquals(0.3f, l2.belowEm)
        val l3 = HtmlTextExtractor.parseParaLayout(mapOf("margin" to "1em"))
        assertEquals(1f, l3!!.aboveEm)
        assertEquals(1f, l3.belowEm)
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
        // #id-sel 与 @font-face 跳过;div.note 与 .indent-2 是两条独立规则
        assertEquals(7, rules.size)
        fun plain(tag: String?, cls: String?) = com.yukino.tool.module.reader.epub.HtmlTextExtractor.SimpleSel(tag, cls)
        val bySpec = rules.associate { it.specificity to it }
        // 单元素 / 单类 / 复合(tag.cls) / 后代链 / 子代链
        assertEquals(plain("p", null), rules.first { it.specificity == 1 }.sel.chain.last())
        assertEquals(plain(null, "center"), rules.first { it.specificity == 10 }.sel.chain.last())
        assertEquals(plain("div", "note"), rules.first { it.specificity == 11 }.sel.chain.last())
        val desc = rules.first { it.sel.chain.size == 3 }
        assertEquals(listOf(plain("li", null), plain("ul", null), plain("li", "c-rules")), desc.sel.chain)
        assertTrue(desc.sel.childAt.isEmpty())
        val child = rules.first { it.sel.childAt.isNotEmpty() }
        assertEquals(listOf(plain("dl", "logo-maker"), plain("dt", null)), child.sel.chain)
        assertEquals(setOf(0), child.sel.childAt)
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
        assertEquals(2f, paras[0].indentEm)          // p 元素规则
        assertEquals(1, paras[1].align)              // .center(类,spec 10) 覆盖 p(spec 1)
        assertEquals(0f, paras[2].indentEm)          // style 属性最高
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
        assertNull(paras[0].indentEm)                // div.contents 不命中 ul.contents
        assertEquals(0f, paras[1].indentEm)          // ul.contents 命中
        // li.c-rules: 命中后代链(祖先链 ul→li)
        val deep = paras.first { it.text.contains("深层规则项") }
        assertEquals(-0.5f, deep.spaceAboveEm)
        val direct = paras.first { it.text.contains("直接子li") }
        assertEquals(1f, direct.spaceAboveEm)        // 子代: li 是 ul.direct 直接子级
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
        assertEquals(0f, l2.indentEm)
        val l3 = HtmlTextExtractor.parseParaLayout(mapOf("margin-top" to "1.5em", "margin-bottom" to "2em"))
        assertEquals(1.5f, l3!!.aboveEm)
        assertEquals(2f, l3.belowEm)
        val l4 = HtmlTextExtractor.parseParaLayout(mapOf("margin" to "1em"))
        assertEquals(1f, l4!!.aboveEm)
        assertEquals(1f, l4.belowEm)
        // px/百分比单位忽略;left/justify 对齐忽略
        assertNull(HtmlTextExtractor.parseParaLayout(mapOf("text-indent" to "20px")))
        assertNull(HtmlTextExtractor.parseParaLayout(mapOf("text-align" to "left")))
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
        assertEquals(0f, paras[0].indentEm)
        assertEquals(2, paras[1].align)              // style 覆盖 class
        assertEquals(1f, paras[2].spaceAboveEm)      // 继承容器的 margin-top
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
            Paragraph("标题段", heading = 2, spaceAboveEm = 1.5f, spaceBelowEm = 2f)
        )
        ChapterFileCodec.write(f, paras)
        val (read, _) = ChapterFileCodec.read(f)
        assertEquals(1, read[0].align)
        assertEquals(0f, read[1].indentEm)
        assertEquals(2, read[2].heading)
        assertEquals(1.5f, read[2].spaceAboveEm)
        assertEquals(2f, read[2].spaceBelowEm)
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
        assertTrue(!com.yukino.tool.module.reader.epub.ChapterFileCodec.needsUpgrade(v7))
        legacy.delete(); v2.delete(); v4.delete(); v5.delete(); v6.delete(); v7.delete()
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
}
