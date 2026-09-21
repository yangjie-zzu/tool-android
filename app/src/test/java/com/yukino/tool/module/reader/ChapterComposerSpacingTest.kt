package com.yukino.tool.module.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 行模型回归: 行分类/段首判定/网格行高/页偏移换算/绘制行生成(缩进、两端对齐拉伸)。
// 全部纯函数,测量注入,不依赖 Android StaticLayout
class ChapterComposerSpacingTest {

    // ---- 行分类 lineKind / 段首判定 paraStart ----

    private val composed = "标题\n\n正文一行\n正文二行"

    @Test
    fun `行分类_标题_空行_正文`() {
        // composed: 标题[0,2) 空[3,3) 正文[4,8) [9,13);bodyStart = 4
        assertEquals(LineKind.TITLE, BookPager.lineKind(composed, 0, 2, 4))
        assertEquals(LineKind.BLANK, BookPager.lineKind(composed, 3, 3, 4))
        assertEquals(LineKind.BODY, BookPager.lineKind(composed, 4, 8, 4))
    }

    @Test
    fun `标题块后的空行与全空白行均为BLANK`() {
        // 全角空格行/空格行/零字符行都是空白
        val ws = "　　"
        assertEquals(LineKind.BLANK, BookPager.lineKind(ws, 0, 2, 0))
        assertEquals(LineKind.BLANK, BookPager.lineKind("a b", 1, 2, 0))
        assertEquals(LineKind.BLANK, BookPager.lineKind(composed, 5, 5, 0))
    }

    @Test
    fun `段首判定_正文区首或紧邻换行`() {
        assertTrue(BookPager.paraStart(composed, 4, 4))    // 正文区首
        assertTrue(BookPager.paraStart(composed, 9, 4))    // 前一字符是 \n
        assertFalse(BookPager.paraStart(composed, 0, 4))   // 标题区不是段落
        assertFalse(BookPager.paraStart(composed, 6, 4))   // 段中行
    }

    // ---- 网格行高 LineGrid ----

    @Test
    fun `行高量化到网格且向上不裁字形`() {
        // 自然高 30 + 行距 10 = 40,恰为网格(4)整数倍
        assertEquals(40, LineGrid.linePitch(30f, 10f, 4))
        // 41 → 向上取整 44
        assertEquals(44, LineGrid.linePitch(31f, 10f, 4))
    }

    @Test
    fun `段前距单独量化`() {
        assertEquals(32, LineGrid.paraAbove(30f, 4))   // 30/4=7.5 → 向上 8 格 → 32
        assertEquals(0, LineGrid.paraAbove(0f, 4))     // 无段距
    }

    @Test
    fun `空行压到一个网格`() {
        assertEquals(4, LineGrid.blankPitch(4))
    }

    @Test
    fun `行距空白对分偏移`() {
        // 行框空白 160-90=70 → 基线下移 35,上下各留 35
        assertEquals(35, LineGrid.centerShift(160, 90f))
        // 无空白时偏移为 0
        assertEquals(0, LineGrid.centerShift(90, 90f))
    }

    // ---- 页偏移换算 globalOffset ----

    @Test
    fun `标题区内页锚定章起点_正文页从正文零点换算`() {
        assertEquals(1000L, BookPager.globalOffset(0, 4, 2000L, 1000L))
        assertEquals(2001L, BookPager.globalOffset(5, 4, 2000L, 1000L))
    }

    // ---- 剥离正文自带标题 stripLeadingTitle ----

    @Test
    fun `剥掉与章名相等的首个非空行及其后空行`() {
        val body = "第一章\n\n正文"
        val (rest, stripped) = ChapterComposer.stripLeadingTitle(body, "第一章")
        assertEquals("正文", rest)
        // 剥掉 "第一章\n\n" 共 5 个字符
        assertEquals(5, stripped)
    }

    @Test
    fun `首个非空行不是章名时原样返回`() {
        val (rest, stripped) = ChapterComposer.stripLeadingTitle("正文\n", "第一章")
        assertEquals("正文\n", rest)
        assertEquals(0, stripped)
    }

    @Test
    fun `空章名不剥`() {
        val (rest, stripped) = ChapterComposer.stripLeadingTitle("正文", "")
        assertEquals("正文", rest)
        assertEquals(0, stripped)
    }

    // ---- 自带缩进判定 leadingIndented ----

    @Test
    fun `全角半角空格与制表符视为自带缩进`() {
        assertTrue(ChapterComposer.leadingIndented("　　段落", 0))
        assertTrue(ChapterComposer.leadingIndented("  段落", 0))
        assertTrue(ChapterComposer.leadingIndented("\t段落", 0))
        assertFalse(ChapterComposer.leadingIndented("段落", 0))
    }

    // ---- 绘制行生成 drawLines ----

    private fun typo(
        indentPx: Float = 0f,
        justify: Boolean = true,
        textWidth: Int = 300
    ) = ResolvedTypography(
        fontPx = 20f, lineExtraPx = 10f, paraExtraPx = 30f, indentPx = indentPx,
        marginPx = 0, textWidth = textWidth, textHeight = 800,
        fgColor = 0, bgColor = 0, justify = justify
    )

    // 等宽假测量: 每字符宽 10
    private val measure10: (String) -> Float = { it.length * 10f }

    @Test
    fun `基线按行高累加_空行占位不绘制_标题行用大字`() {
        val cl = ChapterLines(
            composed, bodyStart = 4, bodyZero = 100L,
            lines = listOf(
                TextLine(0, 2, LineKind.TITLE, isParaStart = false, pitch = 10, paraAbove = 0, ascentAbs = 24),
                TextLine(3, 3, LineKind.BLANK, isParaStart = false, pitch = 4, paraAbove = 0, ascentAbs = 20),
                TextLine(4, 8, LineKind.BODY, isParaStart = true, pitch = 40, paraAbove = 8, ascentAbs = 20)
            )
        )
        val out = BookPager.drawLines(cl, PageSlice(0, 3), typo(), measure10)
        assertEquals(2, out.size)
        // 标题行: 无缩进,基线 = 行顶(0) + 段前距(0) + ascent
        assertEquals("标题", out[0].text)
        assertEquals(0f, out[0].x, 0.001f)
        assertEquals(24f, out[0].baseline, 0.001f)
        assertTrue(out[0].title)
        // 正文段首行: 页首累计行顶 10+4=14,基线 = 14 + 段前距 8 + ascent 20 = 42
        assertEquals("正文一行", out[1].text)
        assertEquals(42f, out[1].baseline, 0.001f)
        assertFalse(out[1].title)
    }

    @Test
    fun `窗口首行是段首行时豁免段前距`() {
        val cl = ChapterLines(
            composed, bodyStart = 4, bodyZero = 0L,
            lines = listOf(
                TextLine(4, 8, LineKind.BODY, isParaStart = true, pitch = 40, paraAbove = 8, ascentAbs = 20),
                TextLine(9, 13, LineKind.BODY, isParaStart = false, pitch = 40, paraAbove = 0, ascentAbs = 20)
            )
        )
        val out = BookPager.drawLines(cl, PageSlice(0, 2), typo(), measure10)
        // 页首段首行顶格: baseline = 0+0+20 = 20;次行 y 推进也按净高 40-8=32 → 32+0+20 = 52
        assertEquals(20f, out[0].baseline, 0.001f)
        assertEquals(52f, out[1].baseline, 0.001f)
    }

    @Test
    fun `页首空行后的段首行同样豁免段前距`() {
        // 窗口首行是 1 格空行(截胡场景): 豁免应落在其后的段首行上
        val cl = ChapterLines(
            composed, bodyStart = 4, bodyZero = 0L,
            lines = listOf(
                TextLine(3, 3, LineKind.BLANK, isParaStart = false, pitch = 4, paraAbove = 0, ascentAbs = 20),
                TextLine(4, 8, LineKind.BODY, isParaStart = true, pitch = 40, paraAbove = 8, ascentAbs = 20)
            )
        )
        val out = BookPager.drawLines(cl, PageSlice(0, 2), typo(), measure10)
        assertEquals(1, out.size)   // 空行不绘制
        // 空行占 4,段首行豁免: baseline = 4 + 0 + 20 = 24
        assertEquals(24f, out[0].baseline, 0.001f)
    }

    @Test
    fun `页中段首行段前距保留`() {
        // 窗口首行是普通行(head),第二行段首在页中 → 段前距照常计入
        val cl = ChapterLines(
            composed, bodyStart = 4, bodyZero = 0L,
            lines = listOf(
                TextLine(0, 2, LineKind.BODY, isParaStart = false, pitch = 40, paraAbove = 0, ascentAbs = 20),
                TextLine(3, 7, LineKind.BODY, isParaStart = true, pitch = 40, paraAbove = 8, ascentAbs = 20)
            )
        )
        val out = BookPager.drawLines(cl, PageSlice(0, 2), typo(), measure10)
        // 首行 baseline 20;次行 y 推进 40 → 40 + 8 + 20 = 68
        assertEquals(20f, out[0].baseline, 0.001f)
        assertEquals(68f, out[1].baseline, 0.001f)
    }

    @Test
    fun `段首行缩进_段中行不缩进`() {
        val cl = ChapterLines(
            composed, bodyStart = 4, bodyZero = 0L,
            lines = listOf(
                TextLine(4, 8, LineKind.BODY, isParaStart = true, pitch = 40, paraAbove = 0, ascentAbs = 20),
                TextLine(9, 13, LineKind.BODY, isParaStart = false, pitch = 40, paraAbove = 0, ascentAbs = 20)
            )
        )
        val out = BookPager.drawLines(cl, PageSlice(0, 2), typo(indentPx = 40f), measure10)
        assertEquals(40f, out[0].x, 0.001f)
        assertEquals(0f, out[1].x, 0.001f)
    }

    @Test
    fun `自带缩进的段落不再叠加缩进`() {
        val cl = ChapterLines(
            "　　自带缩进", bodyStart = 0, bodyZero = 0L,
            lines = listOf(TextLine(0, 6, LineKind.BODY, isParaStart = true, pitch = 40, paraAbove = 0, ascentAbs = 20))
        )
        val out = BookPager.drawLines(cl, PageSlice(0, 1), typo(indentPx = 40f), measure10)
        assertEquals("　　自带缩进", out[0].text)   // 缩进在字符里
        assertEquals(0f, out[0].x, 0.001f)
    }

    @Test
    fun `段中行两端对齐拉伸_段末行保持参差`() {
        // 行 [0,4) 尾后是 '戊'(非换行) → 段中行,拉伸;行 [5,7) 尾后无字符 → 段末行,参差
        val cl = ChapterLines(
            "甲乙丙丁戊\n己庚", bodyStart = 0, bodyZero = 0L,
            lines = listOf(
                TextLine(0, 4, LineKind.BODY, isParaStart = false, pitch = 40, paraAbove = 0, ascentAbs = 20),
                TextLine(5, 7, LineKind.BODY, isParaStart = false, pitch = 40, paraAbove = 0, ascentAbs = 20)
            )
        )
        val out = BookPager.drawLines(cl, PageSlice(0, 2), typo(textWidth = 52), measure10)
        // "甲乙丙丁" 自然宽 40,目标 52 → 3 个间隙各 +4
        val segs = out[0].segments!!
        assertEquals(4, segs.size)
        assertEquals(0f, segs[0].x, 0.001f)
        assertEquals(14f, segs[1].x, 0.001f)
        assertEquals(42f, segs[3].x, 0.001f)
        assertNull(out[1].segments)   // 段末行
    }

    @Test
    fun `关闭两端对齐时不拉伸`() {
        val cl = ChapterLines(
            "甲乙丙丁戊", bodyStart = 0, bodyZero = 0L,
            lines = listOf(TextLine(0, 4, LineKind.BODY, isParaStart = false, pitch = 40, paraAbove = 0, ascentAbs = 20))
        )
        val out = BookPager.drawLines(cl, PageSlice(0, 1), typo(justify = false, textWidth = 52), measure10)
        assertNull(out[0].segments)
    }

    @Test
    fun `标题行不拉伸`() {
        val cl = ChapterLines(
            "标题们", bodyStart = 3, bodyZero = 0L,
            lines = listOf(TextLine(0, 3, LineKind.TITLE, isParaStart = false, pitch = 40, paraAbove = 0, ascentAbs = 24))
        )
        val out = BookPager.drawLines(cl, PageSlice(0, 1), typo(textWidth = 52), measure10)
        assertNull(out[0].segments)
    }
}
