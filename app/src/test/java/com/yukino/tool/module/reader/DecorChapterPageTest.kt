package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.BoxStyle
import com.yukino.tool.module.reader.common.BookPager
import com.yukino.tool.module.reader.common.ChapterLines
import com.yukino.tool.module.reader.common.LineKind
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.common.ResolvedTypography
import com.yukino.tool.module.reader.common.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 装饰章(章首段带装饰盒)分页语义: 整章一页不跨页——页面按快照位图整体呈现。
// 纯 JVM(行模型 + paginate 纯函数),不依赖 Android 运行时
class DecorChapterPageTest {

    private fun typo() = ResolvedTypography(
        fontPx = 19f, lineExtraPx = 5f, paraExtraPx = 19f, indentPx = 38f,
        marginPx = 40, textWidth = 600, textHeight = 800,
        fgColor = 0xFF1A1A1A.toInt(), bgColor = 0xFFF6F1E7.toInt(), justify = true
    )

    // N 行正文行模型(每行 pitch 50),段落区间与行数对齐
    private fun chapterLines(para: Paragraph, nLines: Int): ChapterLines {
        val lines = (0 until nLines).map { i ->
            TextLine(i * 2, i * 2 + 1, LineKind.BODY, i == 0, 50, if (i == 0) 8 else 0, 10)
        }
        return ChapterLines("题\n\n正文", 4, 0L, lines, listOf(para), listOf(0 until 2))
    }

    @Test
    fun `装饰章整章一页不跨页`() {
        val deco = Paragraph("标题段", boxStyle = BoxStyle(bg = 0xFFFFFFFF))
        val cl = chapterLines(deco, nLines = 40)   // 40×50=2000,远超版心高 800
        val windows = BookPager.paginate(cl, typo())
        assertEquals(1, windows.size)
        assertEquals(0, windows[0].startLine)
        assertEquals(40, windows[0].endLineExclusive)
        assertTrue(BookPager.isDecorative(cl))
    }

    @Test
    fun `普通章照常切页`() {
        val normal = Paragraph("普通段")
        val cl = chapterLines(normal, nLines = 40)
        val windows = BookPager.paginate(cl, typo())
        assertTrue(windows.size > 1)
        assertTrue(!BookPager.isDecorative(cl))
    }
}
