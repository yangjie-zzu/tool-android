package com.yukino.tool.module.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// SelectionGeometry 纯函数单测: 命中/选词/选区可视化。measure 注入,无 Android 依赖。
// 度量约定: 每个汉字宽 10,ASCII 字母/数字宽 5;正文行高 20(ascent 16/descent 4),标题 1.4 倍
class SelectionGeometryTest {

    private val fontW = 10f
    private val asciiW = 5f

    // measure: 汉字/标点 10,ASCII 字母数字 5(与行文本构造一一对应,断言可手算)
    private val measure = { t: String, title: Boolean ->
        val unit = if (title) fontW * 1.4f else fontW
        t.sumOf { c -> (if (c.isLetterOrDigit() && c.code < 128) asciiW / 2f else unit / 2f).toDouble() }.toFloat() * 2f
    }

    private val metrics = SelectionGeometry.Metrics(
        bodyAscent = 16f, bodyDescent = 4f, titleAscent = 22f, titleDescent = 7f, measure = measure
    )

    private fun line(
        text: String,
        startGlobal: Long,
        baseline: Float,
        title: Boolean = false,
        x: Float = 0f,
        segments: List<LineSeg>? = null
    ) = DrawLine(text, x, baseline, title, startGlobal, segments)

    private fun page(vararg lines: DrawLine) = BookPage(
        PageSpec(PageKind.CONTENT, 0, 0, 1, lines.firstOrNull()?.lineStartGlobal ?: 0L, "章"),
        "章", "1/1 1%", lines.toList()
    )

    private fun sel(s: Long, e: Long) = ReaderSelection(s, e, anchorIsStart = false)

    // ---- locateLine: 垂直命中 ----

    @Test
    fun `命中行竖直区间与行间空隙就近吸附`() {
        val p = page(
            line("第一行", 0L, baseline = 20f),
            line("第二行", 100L, baseline = 50f)
        )
        // 行内命中
        assertEquals(0, SelectionGeometry.locateLine(p, 20f, metrics))
        assertEquals(1, SelectionGeometry.locateLine(p, 50f, metrics))
        // 行间空隙(第一行 descent 底 24,第二行 ascent 顶 34)→ 取中心更近的第二行
        assertEquals(1, SelectionGeometry.locateLine(p, 31f, metrics))
        assertEquals(0, SelectionGeometry.locateLine(p, 27f, metrics))
        // 上下越界 → 边界行
        assertEquals(0, SelectionGeometry.locateLine(p, 0f, metrics))
        assertEquals(1, SelectionGeometry.locateLine(p, 60f, metrics))
    }

    @Test
    fun `空页返回null`() {
        assertNull(SelectionGeometry.locateLine(page(), 10f, metrics))
    }

    // ---- charOffsetInLine: 水平命中 ----

    @Test
    fun `自然行按字符中心吸附`() {
        val ln = line("四个汉字", 0L, 20f)
        // 每字宽 10: 中心 5 为界
        assertEquals(0, SelectionGeometry.charOffsetInLine(ln, 4f, metrics))
        assertEquals(1, SelectionGeometry.charOffsetInLine(ln, 6f, metrics))
        assertEquals(3, SelectionGeometry.charOffsetInLine(ln, 35f, metrics))
        // 越界钳制
        assertEquals(0, SelectionGeometry.charOffsetInLine(ln, -5f, metrics))
        assertEquals(4, SelectionGeometry.charOffsetInLine(ln, 999f, metrics))
    }

    @Test
    fun `两端对齐行走分段定位`() {
        // 文本 "六个汉字整行",分两段拉伸: 段0 3字起点x=0,段1 3字起点x=60(自然40,拉伸+20)
        val ln = line(
            "六个汉字整行", 0L, 20f,
            segments = listOf(LineSeg("六个汉", 0f), LineSeg("字整行", 60f))
        )
        // 段内自然度量
        assertEquals(1, SelectionGeometry.charOffsetInLine(ln, 12f, metrics))
        // 落到第二段: rel=65 → 段1 内 local=5 → 字符3(第4字)
        assertEquals(3, SelectionGeometry.charOffsetInLine(ln, 65f, metrics))
        // 不走分段会错位: rel=50 在自然度量下落在第5字,分段定位应为段1 的第2字(下标4)
        assertEquals(4, SelectionGeometry.charOffsetInLine(ln, 72f, metrics))
        assertEquals(6, SelectionGeometry.charOffsetInLine(ln, 999f, metrics))
    }

    // ---- wordRange ----

    @Test
    fun `CJK单字成词`() {
        val p = page(line("你好世界", 100L, 20f))
        val (s, e) = SelectionGeometry.wordRange(p, 0, 2)
        assertEquals(102L, s)
        assertEquals(103L, e)
    }

    @Test
    fun `ASCII整词成词`() {
        val p = page(line("你好hello世界", 100L, 20f))
        // "hello" 占字符 2..6
        val (s, e) = SelectionGeometry.wordRange(p, 0, 4)
        assertEquals(102L, s)
        assertEquals(107L, e)
    }

    // ---- visual: 选区矩形 ----

    @Test
    fun `单行选区矩形与手柄锚`() {
        val p = page(line("第一行文字", 0L, 20f))
        val v = SelectionGeometry.visual(p, sel(1L, 4L), metrics)
        assertNotNull(v)
        val r = v!!.rects.single()
        assertEquals(10f, r.left, 0.01f)          // 字符1 左缘
        assertEquals(40f, r.right, 0.01f)          // 字符4 左缘
        assertEquals(20f - 16f - 2f, r.top, 0.01f)
        assertEquals(20f + 4f + 2f, r.bottom, 0.01f)
        assertFalse(v.extendsTop)
        assertFalse(v.extendsBottom)
        assertEquals(r.top, v.startHandle.top, 0.01f)
        assertEquals(r.bottom, v.endHandle.bottom, 0.01f)
    }

    @Test
    fun `跨行选区与跨页延续标志`() {
        val p = page(
            line("第一行文字", 0L, 20f),
            line("第二行文字", 50L, 50f)
        )
        // 从第1行字符3 到第2行字符2
        val v = SelectionGeometry.visual(p, sel(3L, 52L), metrics)!!
        assertEquals(2, v.rects.size)
        assertEquals(30f, v.rects[0].left, 0.01f)
        assertEquals(50f, v.rects[0].right, 0.01f)   // 第1行末尾
        assertEquals(0f, v.rects[1].left, 0.01f)     // 第2行开头
        assertEquals(20f, v.rects[1].right, 0.01f)
        assertFalse(v.extendsTop)
        assertFalse(v.extendsBottom)

        // 越过本页边界 → 延续标志
        val v2 = SelectionGeometry.visual(p, sel(-50L, 2L), metrics)!!
        assertTrue(v2.extendsTop)
        val v3 = SelectionGeometry.visual(p, sel(53L, 999L), metrics)!!
        assertTrue(v3.extendsBottom)
    }

    @Test
    fun `选区与页不相交返回null`() {
        val p = page(line("第一行文字", 100L, 20f))
        assertNull(SelectionGeometry.visual(p, sel(0L, 50L), metrics))
    }

    @Test
    fun `选区contains判定带slop`() {
        val p = page(line("第一行文字", 0L, 20f))
        val v = SelectionGeometry.visual(p, sel(1L, 4L), metrics)!!
        assertTrue(v.contains(20f, 20f, 0f))
        assertTrue(v.contains(45f, 20f, 10f))
        assertFalse(v.contains(80f, 20f, 10f))
    }

    // ---- ReaderSelection.withAnchor ----

    @Test
    fun `拖动端正向延伸与反向翻转`() {
        var s = ReaderSelection(100L, 200L, anchorIsStart = true)
        // 首柄向左扩
        s = s.withAnchor(50L)
        assertEquals(50L to 200L, s.startGlobal to s.endGlobal)
        assertTrue(s.anchorIsStart)
        // 首柄越过尾柄 → 翻转
        s = s.withAnchor(300L)
        assertEquals(200L to 300L, s.startGlobal to s.endGlobal)
        assertFalse(s.anchorIsStart)
        // 尾柄反向越过 → 再翻转
        s = s.withAnchor(80L)
        assertEquals(80L to 200L, s.startGlobal to s.endGlobal)
        assertTrue(s.anchorIsStart)
        // 拖动端恰好压在固定端上 → 保持原选区(不产生空选区)
        s = s.withAnchor(200L)
        assertEquals(80L to 200L, s.startGlobal to s.endGlobal)
        assertTrue(s.anchorIsStart)
    }

    // ---- selectionText: 章界换行 ----

    @Test
    fun `跨章复制补换行`() {
        val book = ReaderBook(
            id = "b", title = "书", sourceUri = "", cachePath = "", encoding = "UTF-8",
            totalChars = 10, chapters = listOf(
                ChapterIndex("一", 0), ChapterIndex("二", 5)
            ),
            addedAt = 0, lastReadAt = 0
        )
        val text = "甲乙丙丁戊己庚辛壬癸"
        val out = selectionText(book, text, ReaderSelection(3, 7, anchorIsStart = false))
        assertEquals("丁戊\n己庚", out)
    }
}
