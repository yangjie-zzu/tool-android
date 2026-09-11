package com.yukino.tool.module.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 回归: 页面物化的段距扣减必须与测量同源(整章级空行判定)。
// 测量时 hasBlank 按整章算;若单页重建时按页内重算,"整章有空行但本页没有"的页
// 每段用全额段前距,行高比测量时更高 → 页尾行被裁掉且后续页不补(目录页缺行 bug)
class ChapterComposerSpacingTest {

    private fun typo() = ResolvedTypography(
        fontPx = 20f, lineExtraPx = 10f, paraExtraPx = 30f, indentPx = 0f,
        marginPx = 0, textWidth = 300, textHeight = 800,
        fgColor = 0, bgColor = 0, justify = false
    )

    @Test
    fun `章内含空行时段距用扣减值`() {
        // paraExtra 30 - lineExtra 10 = 20
        assertEquals(20, ChapterComposer.resolveParaExtra(typo(), hasBlank = true))
    }

    @Test
    fun `章内无空行时段距用全额`() {
        assertEquals(30, ChapterComposer.resolveParaExtra(typo(), hasBlank = false))
    }

    @Test
    fun `扣减值不为负`() {
        // 段距小于行距时扣减后为负,取 0
        val t = typo().copy(paraExtraPx = 5f, lineExtraPx = 10f)
        assertEquals(0, ChapterComposer.resolveParaExtra(t, hasBlank = true))
    }

    @Test
    fun `hasBlankLine判定`() {
        val t = "第一行\n　　\n第三行\n"
        assertTrue(ChapterComposer.hasBlankLine(t, 0, t.length))
        val noBlank = "第一行\n第二行\n"
        assertFalse(ChapterComposer.hasBlankLine(noBlank, 0, noBlank.length))
        // 章级判定只看调用方给出的正文区间;bodyStart 已跳过标题块及其后的换行
        val withTitle = "标题\n\n正文\n"
        assertTrue(ChapterComposer.hasBlankLine(withTitle, 0, withTitle.length))
        assertFalse(ChapterComposer.hasBlankLine(withTitle, 4, withTitle.length))
    }
}
