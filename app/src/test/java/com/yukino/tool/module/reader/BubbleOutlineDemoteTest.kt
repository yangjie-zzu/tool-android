package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.ParaKind
import com.yukino.tool.module.reader.epub.HtmlTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 大圆角+部分边框气泡盒 → WEBVIEW 块降级判定(不再走 BoxStyle 自绘近似)
class BubbleOutlineDemoteTest {

    private fun extractFull(html: String) =
        HtmlTextExtractor.extract(org.jsoup.Jsoup.parseBodyFragment(html).body(), "")

    private fun webCount(html: String) =
        extractFull(html).paragraphs.count { it.kind == ParaKind.WEBVIEW }

    private val style = """<style>
        .bubble{border:5px solid black;border-style:none solid solid none;border-radius:100%;
                width:11em;height:11em;padding:1em;background-color:white}
        .even{border:5px solid black;border-radius:100%;width:11em;height:11em}
        .pxbig{border:5px solid black;border-style:solid none solid solid;border-radius:100px;width:3.5em;height:3.5em}
        .small{border:2px solid black;border-style:solid none none none;border-radius:0.25em;padding:0.2em}
      </style>"""

    @Test fun bubbleOutlineMultiParaDemoted() {
        val n = webCount(style + """<div class="bubble"><p>果然我的<br/>青春恋爱喜剧</p>
            <div><p>1</p></div><p>english line</p></div>""")
        assertEquals(1, n)
    }

    @Test fun uniformBorderLargeRadiusSelfPaint() {
        // 均匀四边+大圆角 = 完整椭圆描边,与标准缩减语义一致,自绘正确不降级
        val n = webCount(style + """<div class="even"><p>第一段</p><p>第二段</p></div>""")
        assertEquals(0, n)
    }

    @Test fun pxBigRadiusPartialBorderDemoted() {
        val n = webCount(style + """<div class="pxbig"><p>true</p><p>or</p><p>false</p></div>""")
        assertEquals(1, n)
    }

    @Test fun smallRadiusPartialBorderSelfPaint() {
        // 小圆角部分边框走直线段/角点路径(与大圆角椭圆象限弧不同),自绘正确不降级
        val n = webCount(style + """<p class="small">普通带框段落文本</p>""")
        assertEquals(0, n)
    }

    @Test fun plainParagraphStillSelfPaint() {
        // 白名单核心回归: 纯文本段/简单均匀装饰段仍自绘(正文不受影响)
        val html = style + """<p>纯文本段落</p><p class="plain">均匀底色边框段</p>"""
        val n = webCount(html.replace("</style>", ".plain{background-color:#fffae0;border:1px solid #ddd}</style>"))
        assertEquals(0, n)
    }

    @Test fun singleParaBubbleDemoted() {
        // 用户标准: 该组合无论单段多段都降级(不接受象限弧近似)
        val n = webCount(style + """<div class="bubble"><p>只有一段的气泡</p></div>""")
        assertEquals(1, n)
    }

    // 真书 title 章: body 容器聚合(子级全为气泡盒/空段) → 单个 WEBVIEW 段
    @Test fun realTitleChapterBodyContainerDemoted() {
        val f = File("D:/projects/tool/tmp_browser_cmp/epub/OEBPS/Text/title.xhtml")
        if (!f.exists()) return   // 环境无关性: 样书缺失时跳过
        val r = HtmlTextExtractor.extract(f, "OEBPS/Text")
        val webs = r.paragraphs.filter { it.kind == ParaKind.WEBVIEW }
        assertEquals(1, webs.size)
        val html = webs[0].blockHtml ?: ""
        assertTrue(html.contains("青春恋爱喜剧"))
        assertTrue(html.contains("false"))
    }

    // 容器含实质文本子段 → 不聚合(正常章节路径不变)
    @Test fun containerWithRealTextNotDemoted() {
        val html = style + """<div class="bubble"><p>气泡段</p></div><p>正文文本段</p>"""
        val r = extractFull(html)
        assertEquals(1, r.paragraphs.count { it.kind == ParaKind.WEBVIEW })
        assertTrue(r.paragraphs.any { it.kind == ParaKind.TEXT && it.text.contains("正文文本段") })
    }
}
