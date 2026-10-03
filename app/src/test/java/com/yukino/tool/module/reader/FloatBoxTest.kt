package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.BookPager
import com.yukino.tool.module.reader.common.ChapterLines
import com.yukino.tool.module.reader.common.CssLen
import com.yukino.tool.module.reader.common.PageSlice
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.common.TextLine
import com.yukino.tool.module.reader.common.LineKind
import com.yukino.tool.module.reader.common.BoxStyle
import com.yukino.tool.module.reader.common.Typography
import org.junit.Test

// float 盒贴右缘的定位验证(真书 title 页 t-box2 场景)
class FloatBoxTest {
    @Test
    fun `float盒贴右缘`() {
        val typo = Typography.resolve(2f, com.yukino.tool.module.reader.common.ReaderSettings(), 800, 1200)
        val box = BoxStyle(
            bg = 0xFFFFFFFFL, radius = CssLen(100f, pct = true),
            heightCss = CssLen(3.5f), widthCss = CssLen(3.5f)
        )
        // 段落序列: 1 个 float 盒组段落(投影占位一行)
        val paras = listOf(
            Paragraph("□true", widthEm = CssLen(3.5f), floatSide = 1, boxStyle = box),
            Paragraph("or", widthEm = CssLen(3.5f), floatSide = 1, boxStyle = box),
            Paragraph("√false", widthEm = CssLen(3.5f), floatSide = 1, boxStyle = box)
        )
        val lines = ArrayList<com.yukino.tool.module.reader.common.TextLine>()
        var pos = 0
        for (p in paras) {
            lines += TextLine(pos, pos + 3, LineKind.BODY, true, 80, 0, 40)
            pos += p.text.length + 1
        }
        val ranges = ArrayList<IntRange>()
        var q = 0
        for (p in paras) { ranges += q until q + p.text.length; q += p.text.length + 1 }
        val cl = ChapterLines(
            paras.joinToString("\n") { it.text }, 0, 0, lines, paras, ranges,
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()
        )
        val slice = com.yukino.tool.module.reader.common.PageSlice(0, lines.size)
        val result = BookPager.drawLinesWithBoxes(cl, slice, typo, measure = { it.length * 30f })
        println("boxes: ${result.boxes}")
        for (b in result.boxes) {
            println("  box left=${b.left} right=${b.right} (版心宽=${typo.textWidth})")
            assert(b.right > typo.textWidth * 0.8f) { "float 盒应贴右缘: right=${b.right}" }
        }
    }
}
