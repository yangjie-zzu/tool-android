package com.yukino.tool.module.reader

import kotlin.random.Random
import org.junit.Assert.assertEquals
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
}
