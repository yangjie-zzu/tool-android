package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.ParaKind
import com.yukino.tool.module.reader.epub.HtmlTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// v30 白名单制判定 + 容器级聚合上提:
//   判定: 标签/属性/值域清单外一律位图(自绘是特许);
//   聚合: 块级子级全位图/空段的容器逐层升位图,分块在最大位图子树的根处整块下发。
class WhitelistAggregateTest {

    private fun extractFull(html: String) =
        HtmlTextExtractor.extract(org.jsoup.Jsoup.parseBodyFragment(html).body(), "")

    // 真书 Section004-0 场景: body > div > table×2(第二张负 margin 贴合第一张)
    // → div/body 逐层上提,整章单块,书内负边距在块内原样保留
    @Test fun divWrappingTwoTablesAggregatesWholeBody() {
        val html = """<div>
<table style="margin:10% auto 0 auto"><tr><td><p>「比企谷」</p></td><td><img src="a.png"/></td></tr></table>
<table style="margin:-3em auto 0 auto"><tr><td><p>「你是笨蛋吗？」</p></td><td><img src="b.png"/></td></tr></table>
</div>"""
        val r = extractFull(html)
        val webs = r.paragraphs.filter { it.kind == ParaKind.WEBVIEW }
        assertEquals(1, webs.size)
        val bh = webs[0].blockHtml!!
        assertTrue(bh.contains("margin:-3em"))
        assertTrue(bh.contains("「你是笨蛋吗？」"))
    }

    // 混合章(位图与实质正文并存): body 分裂,div(全位图)升位图单块,正文照常自绘
    @Test fun mixedChapterSplitsAtSubstantiveContent() {
        val html = """<div>
<table><tr><td><img src="a.png"/></td></tr></table>
</div><p>正文文本段</p>"""
        val r = extractFull(html)
        assertEquals(2, r.paragraphs.size)
        assertEquals(ParaKind.WEBVIEW, r.paragraphs[0].kind)
        assertTrue(r.paragraphs[0].blockHtml!!.startsWith("<div"))
        assertEquals(ParaKind.TEXT, r.paragraphs[1].kind)
        assertEquals("正文文本段", r.paragraphs[1].text)
    }

    // 根块位图不再钳负 margin(v30 删除钳制): 书内负上提原样进块
    @Test fun rootBitmapKeepsNegativeMargin() {
        val r = extractFull("""<table style="margin:-1em auto 0 auto"><tr><td><p>x</p></td></tr></table><p>正文</p>""")
        val web = r.paragraphs.first { it.kind == ParaKind.WEBVIEW }
        assertTrue(web.blockHtml!!.contains("margin:-1em"))
    }

    // 白名单属性轴: 清单外属性 → 位图(不再静默忽略)
    @Test fun unknownPropertyDemotesToBitmap() {
        val r = extractFull("""<p style="letter-spacing:0.1em">疏排正文</p>""")
        assertEquals(1, r.paragraphs.size)
        assertEquals(ParaKind.WEBVIEW, r.paragraphs[0].kind)
    }

    // 白名单回归: 清单内属性(纯文本段/简单盒段)仍自绘
    @Test fun whitelistParagraphsStillSelfPaint() {
        val r = extractFull(
            """<style>p.x{background-color:#fffae0;border:1px solid #ddd;margin:1em 0}</style>""" +
                """<p class="x">简单盒段</p><p>纯文本段</p>"""
        )
        assertEquals(2, r.paragraphs.size)
        assertTrue(r.paragraphs.all { it.kind == ParaKind.TEXT })
    }

    // 卡片完整性(v31): 容器带底色且混有位图子级 → 整容器(含实质自绘子级)单块位图,
    // 底色/边框/阴影连续不被拆裂(果青 Section005 场景)
    @Test fun cardWithBgAndBitmapChildAggregatesWhole() {
        val r = extractFull(
            """<style>div.card{background-color:#fff;border:solid 1px #ddd;box-shadow:2px 3px 3px #000;padding:1em}</style>""" +
                """<div class="card"><p>毕业发展调查表</p>""" +
                """<table><tr><td><img src="a.png"/></td></tr></table>""" +
                """<table><tr><td><p>问卷内容</p></td></tr></table></div>"""
        )
        val webs = r.paragraphs.filter { it.kind == ParaKind.WEBVIEW }
        assertEquals(1, webs.size)
        val bh = webs[0].blockHtml!!
        assertTrue(bh.contains("毕业发展调查表") && bh.contains("问卷内容"))
    }

    // 纯文本卡片(带底色但无位图子级)不受卡片完整性规则影响,维持自绘盒
    @Test fun textOnlyCardKeepsSelfPaint() {
        val r = extractFull(
            """<style>div.card{background-color:#fff;border:solid 1px #ddd}</style>""" +
                """<div class="card"><p>信件第一段</p><p>信件第二段</p></div>"""
        )
        assertEquals(2, r.paragraphs.size)
        assertTrue(r.paragraphs.all { it.kind == ParaKind.TEXT })
        assertTrue(r.paragraphs.all { it.boxStyle != null })
    }

    // v32 fillViewport 收敛: bodyBg 信号只在 body 自带可视背景时为真
    @Test fun bodyBgFlagFollowsBodyBackground() {
        val withBg = org.jsoup.Jsoup.parse(
            """<html><head></head><body style="background-color:#fafafa"><p>正文</p></body></html>"""
        ).body()
        assertTrue(HtmlTextExtractor.extract(withBg, "").bodyBg)

        val withImg = org.jsoup.Jsoup.parse(
            """<html><head></head><body style="background-image:url(bg.png)"><p>正文</p></body></html>"""
        ).body()
        assertTrue(HtmlTextExtractor.extract(withImg, "").bodyBg)

        val plain = org.jsoup.Jsoup.parse(
            """<html><head></head><body><p>正文</p></body></html>"""
        ).body()
        assertTrue(!HtmlTextExtractor.extract(plain, "").bodyBg)
    }
}
