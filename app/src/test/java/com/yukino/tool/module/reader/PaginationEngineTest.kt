package com.yukino.tool.module.reader

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 切页算法与 pageForOffset 的纯逻辑测试: 用确定性假 LineSource,不依赖 Android StaticLayout
class PaginationEngineTest {

    // 假行数据: 每行高 rowHeight,第 n 行行首字符偏移 = 累计行宽(可配置)
    private class FakeLines(
        private val rowHeights: List<Int>,
        private val rowWidths: List<Int>
    ) : LineSource {
        override val lineCount: Int get() = rowHeights.size
        private val tops = rowHeights.runningFold(0) { acc, h -> acc + h }.dropLast(1)
        override fun top(line: Int): Int = tops[line]
        override fun bottom(line: Int): Int = tops[line] + rowHeights[line]
        private val starts = rowWidths.runningFold(0) { acc, w -> acc + w }.dropLast(1)
        override fun start(line: Int): Int = starts[line]
    }

    private fun uniformLines(count: Int, rowHeight: Int, rowWidth: Int) =
        FakeLines(List(count) { rowHeight }, List(count) { rowWidth })

    @Test
    fun `均匀行高的切页数量正确`() {
        // 100 行 × 高10 = 高1000;页高 25 → 每页 2.5 行 → 每页放 2 行? 不:
        // bottom(end)-top(start) <= textHeight → 第1行[0,10) 第2行[0,20) 第3行[0,30)>25 → 每页 2 行
        val pages = PaginationEngine.splitPages(uniformLines(100, 10, 20), textHeight = 25)
        assertEquals(50, pages.size)
        assertEquals(PageSlice(0, 2), pages[0])
        assertEquals(PageSlice(98, 100), pages[49])
    }

    @Test
    fun `页切片连续无遗漏且无空页`() {
        val lines = uniformLines(57, 14, 30)
        val pages = PaginationEngine.splitPages(lines, textHeight = 40)
        assertEquals(lines.lineCount, pages.sumOf { it.endLineExclusive - it.startLine })
        // 连续性: 下一页 startLine == 上一页 endLineExclusive
        pages.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endLineExclusive, b.startLine)
            assertTrue(a.startLine < a.endLineExclusive)
        }
    }

    @Test
    fun `每页高度不超过页高`() {
        val heights = List(80) { if (it % 7 == 0) 30 else 15 }  // 行高不一(预留 MD 场景)
        val widths = List(80) { 10 + it }
        val pages = PaginationEngine.splitPages(FakeLines(heights, widths), textHeight = 60)
        pages.forEach { page ->
            val pageHeight = lines_height(FakeLines(heights, widths), page)
            assertTrue("page $page height=$pageHeight", pageHeight <= 60)
        }
    }

    private fun lines_height(lines: LineSource, page: PageSlice): Int =
        lines.bottom(page.endLineExclusive - 1) - lines.top(page.startLine)

    @Test
    fun `单行超过一页高时强制翻不死循环`() {
        val pages = PaginationEngine.splitPages(FakeLines(listOf(500, 500, 10), listOf(10, 10, 10)), textHeight = 100)
        assertEquals(3, pages.size)
    }

    @Test
    fun `空行数据返回空页列表`() {
        assertEquals(0, PaginationEngine.splitPages(uniformLines(0, 10, 10), textHeight = 100).size)
    }

    @Test
    fun `随机行定位页后页区间必含该行`() {
        val heights = List(300) { 10 + it % 5 }
        val widths = List(300) { 20 }
        val lines = FakeLines(heights, widths)
        val pages = PaginationEngine.splitPages(lines, textHeight = 55)
        val rnd = Random(42)
        repeat(200) {
            val targetLine = rnd.nextInt(lines.lineCount)
            val pageIdx = PaginationEngine.pageForOffset(pages, targetLine)
            assertTrue(pages[pageIdx].containsLine(targetLine))
        }
    }

    @Test
    fun `页首字符偏移序列递增且覆盖全文`() {
        // 行宽 20×300 行,页首偏移必须是 starts 序列中的元素
        val lines = uniformLines(300, 12, 20)
        val pages = PaginationEngine.splitPages(lines, textHeight = 48) // 每页 4 行
        assertEquals(75, pages.size)
        pages.forEachIndexed { i, page ->
            assertEquals(i * 4 * 20, lines.start(page.startLine))
        }
    }

    @Test
    fun `越界行号clamp到末页`() {
        val pages = PaginationEngine.splitPages(uniformLines(10, 10, 5), textHeight = 20)
        assertEquals(pages.lastIndex, PaginationEngine.pageForOffset(pages, 999))
    }

    // 以行宽是否为 0 模拟"空白行"(无可见字符)
    private fun blank(slice: PageSlice, widths: List<Int>) =
        (slice.startLine until slice.endLineExclusive).all { widths[it] == 0 }

    @Test
    fun `裁掉尾部空白页`() {
        val widths = List(10) { if (it >= 8) 0 else 20 }   // 末 2 行为空白行,单独成页
        val pages = PaginationEngine.splitPages(FakeLines(List(10) { 10 }, widths), textHeight = 20)
        // 无裁剪时: [0,2)[2,4)[4,6)[6,8)[8,10), 末页全空白行
        val trimmed = PaginationEngine.trimTrailingBlank(pages) { blank(it, widths) }
        assertEquals(4, trimmed.size)
        assertEquals(PageSlice(6, 8), trimmed.last())
    }

    @Test
    fun `末页含可见字符时不裁剪`() {
        val widths = List(10) { 20 }
        val pages = PaginationEngine.splitPages(uniformLines(10, 10, 20), textHeight = 20)
        val trimmed = PaginationEngine.trimTrailingBlank(pages) { blank(it, widths) }
        assertEquals(pages, trimmed)
    }

    @Test
    fun `全空白时保留末页不返回空列表`() {
        val widths = List(10) { 0 }
        val pages = PaginationEngine.splitPages(FakeLines(List(10) { 10 }, widths), textHeight = 20)
        val trimmed = PaginationEngine.trimTrailingBlank(pages) { blank(it, widths) }
        assertEquals(1, trimmed.size)
        assertEquals(pages.last(), trimmed.last())
    }

    // ---- 垂直匀齐 justifyLineSpacing ----

    // 模拟 relayout: 加 extra 后每行高 rowHeight + extra,整页高 = lineCount × (rowHeight + extra)
    private fun relayoutSim(lineCount: Int, rowHeight: Int): (Float) -> Int =
        { extra -> (lineCount * (rowHeight + extra)).toInt() }

    @Test
    fun `无剩余空白时不加增量`() {
        val (extra, top) = PaginationEngine.justifyLineSpacing(500, 10, 500, 40f, relayoutSim(10, 50))
        assertEquals(0f, extra, 0f)
        assertEquals(0f, top, 0f)
    }

    @Test
    fun `增量分摊后不溢出且零头上下各半`() {
        val (extra, top) = PaginationEngine.justifyLineSpacing(480, 10, 500, 40f, relayoutSim(10, 48))
        assertEquals(2f, extra, 0.001f)
        assertEquals(0f, top, 0.001f)
    }

    @Test
    fun `增量受单行封顶限制`() {
        val (extra, top) = PaginationEngine.justifyLineSpacing(200, 10, 500, 10f, relayoutSim(10, 20))
        assertEquals(10f, extra, 0.001f)
        assertEquals(100f, top, 0.001f)
    }

    @Test
    fun `重排溢出则增量减半重试`() {
        val relayout: (Float) -> Int = { extra ->
            val perLine = kotlin.math.ceil(extra.toDouble()).toInt()
            480 + 10 * perLine * (if (perLine >= 3) 2 else 1)
        }
        val (extra, top) = PaginationEngine.justifyLineSpacing(480, 10, 540, 40f, relayout)
        assertEquals(3f, extra, 0.001f)
        assertEquals(0f, top, 0.001f)
    }

    @Test
    fun `减半仍溢出则回退基础行距`() {
        val (extra, top) = PaginationEngine.justifyLineSpacing(480, 10, 500, 40f) { _ -> 999 }
        assertEquals(0f, extra, 0f)
        assertEquals(0f, top, 0f)
    }

    @Test
    fun `零行返回零增量`() {
        val (extra, top) = PaginationEngine.justifyLineSpacing(0, 0, 500, 40f, relayoutSim(0, 50))
        assertEquals(0f, extra, 0f)
        assertEquals(0f, top, 0f)
    }

    @Test
    fun `模拟真实分摊全程不溢出且行数守恒`() {
        val rnd = Random(7)
        val textHeight = 600
        repeat(20) {
            val lineCount = 3 + rnd.nextInt(30)
            val rowHeight = 20 + rnd.nextInt(30)
            val baseHeight = lineCount * rowHeight
            if (baseHeight <= textHeight) {
                val (extra, top) = PaginationEngine.justifyLineSpacing(
                    baseHeight, lineCount, textHeight, 48f, relayoutSim(lineCount, rowHeight)
                )
                val newHeight = (lineCount * (rowHeight + extra)).toInt()
                assertTrue("page $it overflow: $newHeight", newHeight <= textHeight)
                assertTrue(newHeight + top * 2 <= textHeight + 1)
            }
        }
    }

    // ---- 页末行手工两端对齐 justifySegments ----

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
    fun `含空格按词分间距`() {
        // 词宽 20+20+20=60,目标 80 → 词间 2 个空隙各 +10
        val segs = PaginationEngine.justifySegments("ab cd ef", 80f, 40f, measure10)!!
        assertEquals(listOf("ab", "cd", "ef"), segs.map { it.first })
        assertEquals(0f, segs[0].second, 0.001f)
        assertEquals(30f, segs[1].second, 0.001f)
        assertEquals(60f, segs[2].second, 0.001f)
    }

    @Test
    fun `无拉伸空间返回null`() {
        // 3 字宽 30 = 目标 30 → per=0 → null
        assertNull(PaginationEngine.justifySegments("你好吗", 30f, 40f, measure10))
    }

    @Test
    fun `单字行返回null`() {
        assertNull(PaginationEngine.justifySegments("好", 100f, 40f, measure10))
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
