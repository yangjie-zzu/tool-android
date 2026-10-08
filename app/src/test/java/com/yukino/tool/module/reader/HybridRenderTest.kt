package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.BlockCache
import com.yukino.tool.module.reader.common.BlockGeom
import com.yukino.tool.module.reader.common.ChapterComposer
import com.yukino.tool.module.reader.common.DrawLine
import com.yukino.tool.module.reader.common.ParaKind
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.common.PageKind
import com.yukino.tool.module.reader.common.PageSpec
import com.yukino.tool.module.reader.common.BookPage
import com.yukino.tool.module.reader.epub.ChapterFileCodec
import com.yukino.tool.module.reader.epub.HtmlTextExtractor
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 混合渲染回归(docs/epub-hybrid-render-boundary.md): S1~S5 信号判定与管辖归属、
// 祖先壳/块投影/扁平化规则、章文件 WEBVIEW 段往返、块字符几何表解析、
// SelectionGeometry 块行委托(定位/命中/可视化)。全部纯 JVM,不依赖 Android 运行时
class HybridRenderTest {

    private fun extractHtml(html: String, docDir: String = "") =
        HtmlTextExtractor.extract(Jsoup.parseBodyFragment(html).body(), docDir).paragraphs

    // ---- 信号判定: 边界内不降级 ----

    @Test
    fun `普通段落与块级盒样式不降级`() {
        // 块级元素自身 border/底色是边界内支持(盒组管线),不降级
        val paras = extractHtml(
            "<style>div.bx{border:2px solid #000;background-color:#eee}</style>" +
                "<div class=\"bx\"><p>盒内正文</p></div>"
        )
        assertEquals(1, paras.size)
        assertEquals(ParaKind.TEXT, paras[0].kind)
        assertNotNull(paras[0].boxStyle)
    }

    @Test
    fun `表格整表位图含td边框样式`() {
        // v26: 表格一律整表 WEBVIEW 位图,td 边框/底色由 WebView 原样渲染
        val paras = extractHtml(
            "<style>td{border:1px solid #999;background-color:#f8f8f8}</style>" +
                "<table><tr><td>甲</td><td>乙</td></tr></table>"
        )
        assertEquals(1, paras.size)
        assertEquals(ParaKind.WEBVIEW, paras[0].kind)
    }

    @Test
    fun `img带border不算行内盒装饰`() {
        // 图片描边常见于普通转制书,不属于 S1(行内文本盒装饰)
        val paras = extractHtml(
            "<style>.illus img{border:1px solid #000}</style>" +
                "<div class=\"illus\"><img src=\"a.jpg\"/></div><p>正文</p>"
        )
        assertEquals(2, paras.size)
        assertTrue(paras.all { it.kind != ParaKind.WEBVIEW })
    }

    // ---- 信号判定: 边界外降级 ----

    @Test
    fun `S1行内元素带边框命中块降级`() {
        val paras = extractHtml(
            "<style>.sbox1{background-color:#fff;border:solid 5px #000}</style>" +
                "<p>章号<span class=\"sbox1\">1</span>后文</p>"
        )
        // v25 body 上提: 唯一位图子级并入 body 容器位图
        assertEquals(1, paras.size)
        assertEquals(ParaKind.WEBVIEW, paras[0].kind)
        assertTrue(paras[0].blockHtml!!.startsWith("<body"))
        assertEquals("章号1后文", paras[0].text)
    }

    @Test
    fun `S1管辖归属_td内span归表格整表降级正文不受牵连`() {
        // 果青1 Section003 场景: 圆圈章号 sbox1 在 td 内,最小管辖块是 table——
        // 整表一张位图,body 大 div(包裹整章)与后续正文 p 均不降级
        val html =
            "<style>.sbox1{background-color:#fff;border-radius:100px;border:solid 5px #000;font-size:1.5em}</style>" +
                "<div>" +
                "<table class=\"pius1 bc\"><tr>" +
                "<td class=\"vt\"><span class=\"sbox1\">1</span></td>" +
                "<td class=\"vt\"><p class=\"zin em12\">反正<b>比企谷八幡</b>就是一副死鱼眼</p></td>" +
                "<td class=\"vm\"><img src=\"../Images/s1.png\" style=\"width:4em\"/></td>" +
                "</tr></table>" +
                "<p>国文老师平冢静额头冒着青筋。</p>" +
                "</div>"
        val paras = extractHtml(html)
        assertEquals(2, paras.size)
        assertEquals(ParaKind.WEBVIEW, paras[0].kind)
        assertTrue(paras[0].blockHtml!!.startsWith("<table"))
        assertEquals("1 反正比企谷八幡就是一副死鱼眼", paras[0].text)
        assertEquals(ParaKind.TEXT, paras[1].kind)
        assertEquals("国文老师平冢静额头冒着青筋。", paras[1].text)
    }

    @Test
    fun `S2嵌套表格降级`() {
        val paras = extractHtml(
            "<table><tr><td><table><tr><td>内</td></tr></table></td></tr></table>"
        )
        assertEquals(1, paras.size)
        assertEquals(ParaKind.WEBVIEW, paras[0].kind)
    }

    @Test
    fun `S3内嵌svg命中块降级`() {
        val paras = extractHtml(
            "<div><svg width=\"10\" height=\"10\"><circle r=\"5\"/></svg><p>图示</p></div>"
        )
        assertEquals(1, paras.size)
        assertEquals(ParaKind.WEBVIEW, paras[0].kind)
    }

    @Test
    fun `S4绝对定位与flex命中块降级`() {
        val paras = extractHtml(
            "<style>div.abs{position:absolute}div.fx{display:flex}</style>" +
                "<div class=\"abs\"><p>定位块</p></div><div class=\"fx\"><p>弹性块</p></div>"
        )
        // v25 body 上提: 两个位图子级合并为 body 容器单块
        assertEquals(1, paras.size)
        assertEquals(ParaKind.WEBVIEW, paras[0].kind)
        val bh = paras[0].blockHtml!!
        assertTrue(bh.contains("定位块") && bh.contains("弹性块"))
    }

    @Test
    fun `S5渐变背景命中块降级`() {
        val paras = extractHtml(
            "<style>div.g{background-image:linear-gradient(to right,#fff,#000)}</style>" +
                "<div class=\"g\"><p>渐变</p></div>"
        )
        assertEquals(1, paras.size)
        assertEquals(ParaKind.WEBVIEW, paras[0].kind)
    }

    @Test
    fun `行内渐变背景也命中S1`() {
        val paras = extractHtml(
            "<style>.hl{background-image:linear-gradient(#fff,#000)}</style>" +
                "<p>高亮<span class=\"hl\">渐变字</span>结束</p>"
        )
        assertEquals(1, paras.size)
        assertEquals(ParaKind.WEBVIEW, paras[0].kind)
    }

    // ---- 祖先壳 ----

    @Test
    fun `祖先壳含body属性与逐层开标签`() {
        val doc = Jsoup.parse(
            "<html><head></head><body class=\"main\" style=\"color:#000\">" +
                "<div class=\"set-box1\"><p id=\"t\">内容</p></div></body></html>"
        )
        val p = doc.body().selectFirst("p")!!
        val shell = HtmlTextExtractor.ancestorShellOf(p)
        assertTrue(shell.startsWith("<body class=\"main\" style=\"color:#000\">"))
        assertTrue(shell.contains("<div class=\"set-box1\">"))
        // html 元素不入壳;壳是纯开标签序列(无闭合)
        assertEquals(false, shell.contains("</"))
        assertEquals(false, shell.contains("<html"))
    }

    // ---- 块投影扁平化(与 WebView 采集 JS 同一条规则) ----

    @Test
    fun `扁平化_块边界折空格_连续空白压一_trim`() {
        val doc = Jsoup.parseBodyFragment(
            "<div><p>甲</p>\n  <p>乙</p><div>丙   丁</div></div>"
        ).body().selectFirst("div")!!
        // 块边界折空格: 甲/乙/丙丁 之间各一空格;源码换行缩进空白折叠;无首尾空格
        assertEquals("甲 乙 丙 丁", HtmlTextExtractor.flattenBlockText(doc))
    }

    @Test
    fun `扁平化_br折空格与svg跳过`() {
        val doc = Jsoup.parseBodyFragment(
            "<div>甲<br/>乙<script>evil()</script><svg><text>svg字</text></svg></div>"
        ).body().selectFirst("div")!!
        assertEquals("甲 乙", HtmlTextExtractor.flattenBlockText(doc))
    }

    @Test
    fun `扁平化_全角空格不折叠`() {
        val doc = Jsoup.parseBodyFragment("<p>　全角　空格</p>").body().selectFirst("p")!!
        // U+3000 是 CJK 排版实字符: 不折叠,段首也不裁(与浏览器/采集 JS 同语义)
        assertEquals("　全角　空格", HtmlTextExtractor.flattenBlockText(doc))
    }

    @Test
    fun `空投影块落U+FFFC占位`() {
        // 信号命中但无可见文字的块(纯 svg 图示): 投影空,落单 U+FFFC 维持行模型可挂
        val paras = extractHtml("<div><svg width=\"8\" height=\"8\"><circle r=\"4\"/></svg></div><p>后文</p>")
        assertEquals(2, paras.size)
        assertEquals(ParaKind.WEBVIEW, paras[0].kind)
        assertEquals("\uFFFC", paras[0].text)
    }

    // ---- 章文件往返 ----

    @Test
    fun `WEBVIEW段章文件往返`() {
        val f = File.createTempFile("ch_v19", ".txt")
        val paras = listOf(
            Paragraph(
                "1 反正比企谷八幡就是一副死鱼眼", emptyList(), ParaKind.WEBVIEW,
                align = 1,
                blockHtml = "<table class=\"pius1\"><tr><td><span class=\"sbox1\">1</span></td></tr></table>",
                ancestorShell = "<body class=\"main\"><div>",
                blockDocDir = "Text"
            ),
            Paragraph("正文")
        )
        ChapterFileCodec.write(f, paras, cssHrefs = listOf("../Styles/style.css"), cssInline = listOf("p{}"))
        val read = ChapterFileCodec.read(f)
        assertEquals(ParaKind.WEBVIEW, read.paragraphs[0].kind)
        assertEquals("1 反正比企谷八幡就是一副死鱼眼", read.paragraphs[0].text)
        assertEquals(1, read.paragraphs[0].align)
        assertEquals("<table class=\"pius1\"><tr><td><span class=\"sbox1\">1</span></td></tr></table>", read.paragraphs[0].blockHtml)
        assertEquals("<body class=\"main\"><div>", read.paragraphs[0].ancestorShell)
        assertEquals("Text", read.paragraphs[0].blockDocDir)
        assertEquals(listOf("../Styles/style.css"), read.cssHrefs)
        assertEquals(listOf("p{}"), read.cssInline)
        assertTrue(!ChapterFileCodec.needsUpgrade(f))
        f.delete()
    }

    // ---- 几何表 JSON 解析 ----

    @Test
    fun `几何表JSON解析与序列矩形数防御`() {
        val json = "{\"ok\":1,\"w\":800,\"h\":300,\"cs\":\"甲乙\",\"rs\":[[10,20,30,40],[50,60,70,80]]}"
        val g = BlockCache.parseGeomJson(json)
        assertNotNull(g)
        assertEquals("甲乙", g!!.chars)
        assertEquals(2, g.charCount)
        assertEquals(10f, g.rects[0])
        assertEquals(80f, g.rects[7])
        assertEquals(800, g.contentW)
        // 序列与矩形数不一致 → 拒用(选择走兜底)
        assertNull(BlockCache.parseGeomJson("{\"ok\":1,\"cs\":\"甲乙\",\"rs\":[[1,2,3,4]]}"))
        assertNull(BlockCache.parseGeomJson("{\"ok\":0,\"cs\":\"\",\"rs\":[]}"))
    }

    // ---- SelectionGeometry 块行委托 ----

    private val metrics = SelectionGeometry.Metrics(20f, 5f, 28f, 7f) { t, _ -> t.length * 10f }

    private fun blockLine(): DrawLine {
        // 4 字符几何表(显示域坐标,相对块左上): 一行 2 字 + 换行 2 字
        val rects = floatArrayOf(
            0f, 10f, 40f, 50f,    // 甲
            40f, 10f, 40f, 50f,   // 乙
            0f, 70f, 40f, 50f,    // 丙
            40f, 70f, 40f, 50f    // 丁
        )
        return DrawLine(
            "甲乙丙丁", 0f, 100f, false, 500L,
            blockRef = "/x/webp", blockWidth = 80f, blockHeight = 130f,
            blockGeom = BlockGeom("甲乙丙丁", rects, 80, 130)
        )
    }

    private fun pageOf(lines: List<DrawLine>) = BookPage(
        PageSpec(PageKind.CONTENT, 0, 0, 1, 0L, "章"),
        "章", "1/1", lines = lines
    )

    @Test
    fun `块行垂直定位按位图整高区间`() {
        val page = pageOf(listOf(blockLine()))
        // 块行区间 [baseline, baseline+blockHeight] = [100, 230]
        assertEquals(0, SelectionGeometry.locateLine(page, 100f, metrics))
        assertEquals(0, SelectionGeometry.locateLine(page, 220f, metrics))
    }

    @Test
    fun `块行字符命中按几何表最近吸附`() {
        val ln = blockLine()
        // (45,30) → 第 2 字符乙(中心 60,35);几何坐标相对块左上,入参为版心坐标(x 偏移 +5)
        assertEquals(1, SelectionGeometry.charOffsetInLine(ln, 45f, 135f, metrics))
        // 第二行丙(中心 20,95 → 版心 y = 100+95=195)
        assertEquals(2, SelectionGeometry.charOffsetInLine(ln, 20f, 195f, metrics))
        // 无几何表 → 0(选择退化,不建选区)
        val noGeom = DrawLine("文", 0f, 0f, false, 0L, blockRef = "/x", blockWidth = 10f, blockHeight = 10f)
        assertEquals(0, SelectionGeometry.charOffsetInLine(noGeom, 5f, 5f, metrics))
    }

    @Test
    fun `块行选区高亮为几何表矩形并集`() {
        val page = pageOf(listOf(blockLine()))
        val visual = SelectionGeometry.visual(page, ReaderSelection(500L, 504L, anchorIsStart = true), metrics)!!
        // 全选 4 字: 两行横条(几何 y 10..60 与 70..120)
        assertEquals(2, visual.rects.size)
        // 首横条 x 覆盖 [0,80](版心 x 偏移 0);顶 = baseline+minY+30%行框高-2(行框顶部裁剪 30%)
        assertEquals(0f, visual.rects[0].left, 0.01f)
        assertEquals(123f, visual.rects[0].top, 0.01f)
        assertEquals(80f, visual.rects[0].right, 0.01f)
        // 第二横条顶 = baseline+70+15-2
        assertEquals(183f, visual.rects[1].top, 0.01f)
        // 手柄锚: 首字符左缘 / 末字符右缘
        assertEquals(0f, visual.startHandle.left, 0.01f)
        assertEquals(80f, visual.endHandle.left, 0.01f)
    }

    @Test
    fun `块行无几何表时选区高亮为空`() {
        val line = DrawLine("文", 0f, 0f, false, 0L, blockRef = "/x", blockWidth = 10f, blockHeight = 10f)
        val page = pageOf(listOf(line))
        assertNull(SelectionGeometry.visual(page, ReaderSelection(0L, 1L, anchorIsStart = true), metrics))
    }

    // ---- 词边界(投影文本照常工作) ----

    @Test
    fun `块行wordRange按投影文本工作`() {
        val page = pageOf(listOf(blockLine()))
        val (s, e) = SelectionGeometry.wordRange(page, 0, 1)
        // 第 2 字符"乙"非 ASCII 单词字符 → 单字符选区 [501, 502)
        assertEquals(501L, s)
        assertEquals(502L, e)
    }

    // ---- hideTitleRow 判定素材(章首 WEBVIEW 块投影去序号 == 章名) ----

    @Test
    fun `章首装饰块投影去序号与章名一致`() {
        val blockText = "1 反正比企谷八幡就是一副死鱼眼"
        val title = "① 反正比企谷八幡就是一副死鱼眼"
        // 与 BookPager.stripOrdinalLoose 同规则(去首部序号修饰后比较)
        val strip = { s: String ->
            s.trim().trimStart('0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                '①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩', ' ', '　', '.', '、')
        }
        assertEquals(strip(title), strip(blockText))
    }
}
