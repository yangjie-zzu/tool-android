package com.yukino.tool.module.reader

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 切页/网格/两端对齐的纯逻辑测试: 确定性数据,不依赖 Android StaticLayout
class PaginationEngineTest {

    // ---- 网格对齐 gridCeil ----

    @Test
    fun `网格取整向上不裁字形`() {
        assertEquals(40, PaginationEngine.gridCeil(40f, 2))
        assertEquals(42, PaginationEngine.gridCeil(41f, 2))
        assertEquals(2, PaginationEngine.gridCeil(1f, 2))
        assertEquals(44, PaginationEngine.gridCeil(43.5f, 4))
    }

    // ---- 版心贴合 fitPitch(满页排版) ----

    @Test
    fun `行高放大到版心均分`() {
        // 1923/152 = 12 行余 99;放大为 floor(1923/12/2)*2 = 160,12×160 = 1920,余 3
        assertEquals(160, PaginationEngine.fitPitch(1923, 152, 2))
    }

    @Test
    fun `版心恰为行高整数倍时不放大`() {
        assertEquals(150, PaginationEngine.fitPitch(1800, 150, 2))
    }

    @Test
    fun `版心小于一行时返回基准行高`() {
        assertEquals(152, PaginationEngine.fitPitch(100, 152, 2))
        assertEquals(152, PaginationEngine.fitPitch(152, 152, 2))
    }

    @Test
    fun `随机版心下放大不变式_网格倍数_不裁字_不溢出`() {
        val rnd = Random(9)
        repeat(50) {
            val base = 2 * (20 + rnd.nextInt(60))
            val textHeight = base + rnd.nextInt(base * 3)
            val p = PaginationEngine.fitPitch(textHeight, base, 2)
            assertTrue("p=$p >= base=$base", p >= base)          // 不裁字形
            assertEquals(0, p % 2)                               // 网格倍数
            val n = textHeight / base
            assertTrue("n*p=${n * p} <= th=$textHeight", n * p <= textHeight)   // 不溢出
            // 页底余数必须小于基准行高(满页效果)
            assertTrue("leftover=${textHeight - n * p}", textHeight - n * p < base)
        }
    }

    // ---- 行窗口切页 splitPages(lines) ----

    // 构造行: pitch 占位、段前距(paraAbove>0 即段首行)、blank=空行
    private fun tl(pitch: Int, paraAbove: Int = 0, blank: Boolean = false) = TextLine(
        start = 0, end = if (blank) 0 else 1,
        kind = if (blank) LineKind.BLANK else LineKind.BODY,
        isParaStart = paraAbove > 0,
        pitch = pitch, paraAbove = paraAbove, ascentAbs = 20
    )

    @Test
    fun `均匀行高的切页数量正确`() {
        // 100 行 × 高10 = 高1000;页高 25 → 每页放 2 行
        val pages = PaginationEngine.splitPages(List(100) { tl(10) }, textHeight = 25)
        assertEquals(50, pages.size)
        assertEquals(PageSlice(0, 2), pages[0])
        assertEquals(PageSlice(98, 100), pages[49])
    }

    @Test
    fun `页切片连续无遗漏且无空页`() {
        val lines = List(57) { tl(14) }
        val pages = PaginationEngine.splitPages(lines, textHeight = 40)
        assertEquals(lines.size, pages.sumOf { it.endLineExclusive - it.startLine })
        // 连续性: 下一页 startLine == 上一页 endLineExclusive
        pages.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endLineExclusive, b.startLine)
            assertTrue(a.startLine < a.endLineExclusive)
        }
    }

    private fun pageHeight(lines: List<TextLine>, page: PageSlice): Int =
        lines.subList(page.startLine, page.endLineExclusive).sumOf { it.pitch }

    @Test
    fun `每页高度不超过页高`() {
        val lines = List(80) { tl(if (it % 7 == 0) 30 else 15) }
        val pages = PaginationEngine.splitPages(lines, textHeight = 60)
        pages.forEach { page ->
            val h = pageHeight(lines, page)
            assertTrue("page $page height=$h", h <= 60)
        }
    }

    @Test
    fun `单行超过一页高时强制翻不死循环`() {
        val pages = PaginationEngine.splitPages(List(3) { tl(500) }, textHeight = 100)
        assertEquals(3, pages.size)
    }

    @Test
    fun `空行数据返回空页列表`() {
        assertEquals(0, PaginationEngine.splitPages(emptyList(), textHeight = 100).size)
    }

    // ---- 页首行豁免段前距 ----

    @Test
    fun `页首段首行豁免段前距后该页多容纳内容`() {
        // 段首行占位 391(段前 231+净 160),后续两行各 160;页高 480
        // 豁免: 160+160+160=480 恰好单页;不豁免则首行 391 后只能再放 1 行
        val lines = listOf(tl(391, 231), tl(160), tl(160))
        val pages = PaginationEngine.splitPages(lines, textHeight = 480)
        assertEquals(listOf(PageSlice(0, 3)), pages)
    }

    @Test
    fun `页中段首行段前距照常计入`() {
        // 第 2 行是段首行(391),它在页中,段前距不豁免
        val lines = listOf(tl(160), tl(391, 231), tl(160))
        val pages = PaginationEngine.splitPages(lines, textHeight = 551)
        assertEquals(listOf(PageSlice(0, 2), PageSlice(2, 3)), pages)
    }

    @Test
    fun `豁免仅按窗口首行判定_每页各自豁免`() {
        // 每页首行都豁免后恰满页: 页高 480,行 [391,160,160,391,160,160]
        val lines = listOf(tl(391, 231), tl(160), tl(160), tl(391, 231), tl(160), tl(160))
        val pages = PaginationEngine.splitPages(lines, textHeight = 480)
        assertEquals(listOf(PageSlice(0, 3), PageSlice(3, 6)), pages)
    }

    @Test
    fun `页首空行不截胡豁免_空行后的段首行同样顶格`() {
        // 一个 1 格的空行恰好成为窗口首行: 豁免应落在其后的段首行上
        // 占位: 空行1 + 段首净高160 + 160 + 160 = 481 ≤ 481 → 单页
        val lines = listOf(tl(1, blank = true), tl(391, 231), tl(160), tl(160))
        val pages = PaginationEngine.splitPages(lines, textHeight = 481)
        assertEquals(listOf(PageSlice(0, 4)), pages)
    }

    // ---- 切页守恒: 除末页外每页必然"再放一行放不下" ----

    @Test
    fun `除末页外每页再放一行必超页高`() {
        val rnd = Random(42)
        repeat(50) {
            val pitches = List(1 + rnd.nextInt(40)) { 2 * (1 + rnd.nextInt(15)) }   // 全为 2 的倍数
            val textHeight = 200 + rnd.nextInt(50)                                   // 任意值
            val lines = pitches.map { tl(it) }
            val pages = PaginationEngine.splitPages(lines, textHeight)
            pages.dropLast(1).forEach { p ->
                val h = pageHeight(lines, p)
                assertTrue(h <= textHeight)
            }
        }
    }

    // ---- 裁掉尾部空白页 trimTrailingBlank ----

    @Test
    fun `裁掉尾部空白页`() {
        val blanks = List(10) { it >= 8 }   // 末 2 行为空白行
        val lines = List(10) { tl(10, blank = blanks[it]) }
        val pages = PaginationEngine.splitPages(lines, textHeight = 20)
        // 无裁剪时: [0,2)[2,4)[4,6)[6,8)[8,10), 末页全空白行
        val trimmed = PaginationEngine.trimTrailingBlank(pages) { w ->
            (w.startLine until w.endLineExclusive).all { blanks[it] }
        }
        assertEquals(4, trimmed.size)
        assertEquals(PageSlice(6, 8), trimmed.last())
    }

    @Test
    fun `末页含可见字符时不裁剪`() {
        val pages = PaginationEngine.splitPages(List(10) { tl(10) }, textHeight = 20)
        val trimmed = PaginationEngine.trimTrailingBlank(pages) { false }
        assertEquals(pages, trimmed)
    }

    @Test
    fun `全空白时保留末页不返回空列表`() {
        val pages = PaginationEngine.splitPages(List(10) { tl(10, blank = true) }, textHeight = 20)
        val trimmed = PaginationEngine.trimTrailingBlank(pages) { true }
        assertEquals(1, trimmed.size)
        assertEquals(pages.last(), trimmed.last())
    }

    // ---- 拉伸词元 tokenize ----

    @Test
    fun `西文词累积且空格粘前词尾`() {
        assertEquals(listOf("ab ", "cd"), PaginationEngine.tokenize("ab cd"))
        assertEquals(listOf("word"), PaginationEngine.tokenize("word"))
    }

    @Test
    fun `宽字符逐字成元`() {
        assertEquals(listOf("你", "好", "ab"), PaginationEngine.tokenize("你好ab"))
    }

    @Test
    fun `空串无词元`() {
        assertEquals(emptyList<String>(), PaginationEngine.tokenize(""))
    }

    // ---- 行两端对齐 justifySegments ----

    // 等宽假测量: 每字符宽 10
    private val measure10: (String) -> Float = { it.length * 10f }

    @Test
    fun `等宽字符间距均分到每字之间`() {
        // 5 字 × 10 = 50,目标 90 → 4 个间隙各 +10
        val segs = PaginationEngine.justifySegments("你好吗好吗", 90f, 40f, measure10)!!
        assertEquals(5, segs.size)
        assertEquals(0f, segs[0].second, 0.001f)
        assertEquals(20f, segs[1].second, 0.001f)
        assertEquals(80f, segs[4].second, 0.001f)
    }

    @Test
    fun `含空格时保留空格宽只补差额`() {
        // 词元 ["ab ","cd ","ef"] 自然宽 80,目标 110 → 2 个间隙各 +15
        val segs = PaginationEngine.justifySegments("ab cd ef", 110f, 40f, measure10)!!
        assertEquals(listOf("ab ", "cd ", "ef"), segs.map { it.first })
        assertEquals(0f, segs[0].second, 0.001f)
        assertEquals(45f, segs[1].second, 0.001f)
        assertEquals(90f, segs[2].second, 0.001f)
    }

    @Test
    fun `无拉伸空间返回null`() {
        // 3 字宽 30 = 目标 30 → per=0 → null
        assertNull(PaginationEngine.justifySegments("你好吗", 30f, 40f, measure10))
    }

    @Test
    fun `单词元行返回null`() {
        // 纯西文长词只有 1 个词元,无处拉伸
        assertNull(PaginationEngine.justifySegments("abcdef", 200f, 40f, measure10))
    }

    @Test
    fun `间距超上限返回null`() {
        // 2 字宽 20,目标 110 → 单处 90 > 40 → null
        assertNull(PaginationEngine.justifySegments("你好", 110f, 40f, measure10))
    }

    @Test
    fun `空串返回null`() {
        assertNull(PaginationEngine.justifySegments("", 100f, 40f, measure10))
    }
}
