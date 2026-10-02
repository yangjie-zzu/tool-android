package com.yukino.tool.module.reader.epub

import java.io.File
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import com.yukino.tool.module.reader.common.ParaKind
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.common.Run
import com.yukino.tool.module.reader.common.RunStyle

// XHTML → 段落序列(二期富文本形态)。规则:
//   块级元素边界 = 段落边界;<br> 断段;script/style 等剥离;
//   段内空白规整(连续空白折叠为一,CJK 字符间的粘连空格去除)与 Run 边界记录
//   在同一次遍历完成——规整会改变文本长度,事后映射必错位;
//   内联样式标签与 style 属性合并为 Run(段内坐标),内联标签不是段落边界;
//   <img> 独立成 IMAGE 段落(投影占位 U+FFFC),imageRef 为解压目录内相对路径;
//   ul/ol>li 加 "• "/"N. " 前缀;简单表格逐行"单元格 | 单元格",
//   含跨行跨列或嵌套的复杂表出占位段。
// 纯文本投影规则与一期一致(同一段 HTML 产出的投影文本相同,仅增补样式与图片段)
object HtmlTextExtractor {

    // 完全剥离的元素(不可见内容/外部资源;svg 内的 <image> 四期 SVG 栅格化时启用)
    private val SKIP = hashSetOf("script", "style", "head", "title", "svg", "link", "meta", "iframe", "object", "video", "audio", "canvas", "template")

    // 块级元素: 进入/离开都是段落边界(ol/ul/li/table 在遍历中特判,不走此集合)
    private val BLOCK = hashSetOf(
        "p", "div", "section", "article", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6", "thead", "tbody", "tfoot",
        "tr", "td", "th", "dl", "dt", "dd", "pre", "aside", "figure", "figcaption",
        "header", "footer", "main", "nav", "hr", "center", "form", "address", "caption"
    )

    // 内联标签 → 样式位
    private val INLINE_STYLE = mapOf(
        "b" to RunStyle.BOLD, "strong" to RunStyle.BOLD,
        "i" to RunStyle.ITALIC, "em" to RunStyle.ITALIC, "cite" to RunStyle.ITALIC, "var" to RunStyle.ITALIC,
        "u" to RunStyle.UNDERLINE, "ins" to RunStyle.UNDERLINE,
        "s" to RunStyle.STRIKE, "del" to RunStyle.STRIKE, "strike" to RunStyle.STRIKE,
        "sup" to RunStyle.SUP, "sub" to RunStyle.SUB
    )

    // 图片段落的投影占位字符(单字符,保持全书偏移轴连续)
    const val IMAGE_PLACEHOLDER = "\uFFFC"

    private const val TABLE_PLACEHOLDER = "[表格内容，建议使用原版式查看]"

    fun extract(file: File, docDir: String = ""): List<Paragraph> =
        extract(Jsoup.parse(file, "UTF-8").body(), docDir)

    // 文档内首个标题(h1..h6,按序),供章节名兜底;无标题返回 null
    fun firstHeading(file: File): String? {
        val body = Jsoup.parse(file, "UTF-8").body()
        for (tag in listOf("h1", "h2", "h3", "h4", "h5", "h6")) {
            val e = body.selectFirst(tag) ?: continue
            val s = e.text().trim()
            if (s.isNotEmpty()) return s
        }
        return null
    }

    fun extract(body: Element, docDir: String = ""): List<Paragraph> {
        val b = Builder(docDir)
        walk(body, b, ArrayDeque())
        b.flush()
        return b.result()
    }

    // ---------- 段缓冲: 规整文本与 Run 边界一体化记录 ----------
    //
    // 流式规则: 空白字符不立即落盘,记 pendingSpace;遇到实字符时决定空格归属——
    //   段首(此前无内容)丢弃;前后都是 CJK 丢弃(HTML 源码换行伪影);否则落一个 ' '。
    // 空格与实字符都按"落盘时刻"的当前样式记入 Run;相邻同样式 Run 天然合为一段
    internal class Builder(val docDir: String) {
        private val out = ArrayList<Paragraph>()
        private val sb = StringBuilder()
        private val runs = ArrayList<Run>()
        var curStyle = 0
        private var pendingSpace = false

        fun result(): List<Paragraph> = out

        // CJK 字符(汉字/CJK标点/全角): 与一期 CJK_GLUE 同一区间
        private fun isCjk(c: Char): Boolean =
            c.code in 0x2E80..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF

        private fun openRun() {
            val last = runs.lastOrNull()
            if (last == null || last.style != curStyle) runs += Run(sb.length, sb.length, curStyle)
        }

        fun appendText(s: String) {
            for (c in s) {
                if (c.isWhitespace()) { pendingSpace = true; continue }
                if (pendingSpace) {
                    pendingSpace = false
                    if (sb.isNotEmpty() && !(isCjk(sb[sb.length - 1]) && isCjk(c))) {
                        openRun()
                        sb.append(' ')
                    }
                }
                openRun()
                sb.append(c)
                runs[runs.lastIndex] = runs.last().copy(end = sb.length)
            }
        }

        // 段落收口: 全空白段丢弃(空白从未落盘,无需 trim);无有效样式的 Run 退化为空
        fun flush() {
            if (sb.isNotBlank()) {
                var rs = runs.filter { it.end > it.start }
                if (rs.all { it.style == 0 }) rs = emptyList()
                out += Paragraph(sb.toString(), rs)
            }
            sb.setLength(0)
            runs.clear()
            pendingSpace = false
        }

        fun addImage(ref: String) {
            out += Paragraph(IMAGE_PLACEHOLDER, emptyList(), ParaKind.IMAGE, ref)
        }

        fun addPlainPara(text: String) {
            if (text.isNotEmpty()) out += Paragraph(text)
        }
    }

    // ---------- 树遍历 ----------

    private fun walk(node: Node, b: Builder, counters: ArrayDeque<Int?>) {
        when (node) {
            is TextNode -> b.appendText(node.text())
            is Element -> walkElement(node, b, counters)
        }
    }

    private fun walkElement(node: Element, b: Builder, counters: ArrayDeque<Int?>) {
        val name = node.tagName().lowercase()
        if (name in SKIP) return

        // style 属性近似解析(CSS 继承语义的简化: 作用于本元素的全部子孙文本)
        val attrStyle = parseStyleAttr(node.attr("style"))
        val saved = b.curStyle
        if (attrStyle != 0) b.curStyle = saved or attrStyle

        when {
            name == "br" -> b.flush()
            name == "img" -> { b.flush(); emitImage(node, b) }
            name == "table" -> { b.flush(); emitTable(node, b) }
            INLINE_STYLE.containsKey(name) -> {
                b.curStyle = saved or (INLINE_STYLE[name] ?: 0)
                for (c in node.childNodes()) walk(c, b, counters)
            }
            name == "ol" -> { counters.addLast(0); walkBlock(node, b, counters); counters.removeLast() }
            name == "ul" -> { counters.addLast(null); walkBlock(node, b, counters); counters.removeLast() }
            name == "li" -> {
                b.flush()   // 结束上一段;前缀之后的内容同段,不再 flush(前缀与首行同段落)
                val n = counters.lastOrNull()
                if (n != null) { counters[counters.lastIndex] = n + 1; b.appendText("${n + 1}. ") }
                else b.appendText("• ")
                for (c in node.childNodes()) walk(c, b, counters)
                b.flush()
            }
            name in BLOCK -> { b.flush(); for (c in node.childNodes()) walk(c, b, counters); b.flush() }
            else -> for (c in node.childNodes()) walk(c, b, counters)
        }

        b.curStyle = saved
    }

    // 块级元素: 前后都是段落边界
    private fun walkBlock(node: Element, b: Builder, counters: ArrayDeque<Int?>) {
        b.flush()
        for (c in node.childNodes()) walk(c, b, counters)
        b.flush()
    }

    // img: 任何位置独立成图片段(外部 URL/data URI 忽略——外部资源不入正文)
    private fun emitImage(node: Element, b: Builder) {
        val src = node.attr("src").trim()
        if (src.isEmpty() || src.startsWith("http", true) || src.startsWith("data:")) return
        val ref = resolveHref(b.docDir, percentDecode(src))
        if (ref.isNotBlank()) b.addImage(ref)
    }

    // 表格: 含跨行跨列或嵌套的复杂表出占位段;简单表逐行"单元格 | 单元格"
    // (单元格内样式忽略——降级场景;空行丢弃)
    private fun emitTable(node: Element, b: Builder) {
        if (node.selectFirst("[rowspan],[colspan], table table") != null) {
            b.addPlainPara(TABLE_PLACEHOLDER)
            return
        }
        for (tr in node.select("tr")) {
            val cells = tr.select("th,td").map { collapse(it.text()) }.filter { it.isNotEmpty() }
            if (cells.isNotEmpty()) b.addPlainPara(cells.joinToString(" | "))
        }
    }

    // 单元格文本规整(整段产出无 Run,不走 Builder)
    private fun collapse(s: String): String {
        val ws = Regex("[\\t\\n\\x0B\\f\\r ]+")
        val cjkGlue = Regex(
            "(?<=[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF]) +(?=[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])"
        )
        return cjkGlue.replace(ws.replace(s, " ").trim(), "")
    }

    // 内联 style 属性 → 样式位(宽容匹配,认不出的属性忽略——产品原则: 书内样式不干扰全局阅读设置)
    internal fun parseStyleAttr(style: String): Int {
        if (style.isBlank()) return 0
        var s = 0
        val norm = style.lowercase().replace("\"", "'")
        if (Regex("font-weight\\s*:\\s*(bold|[7-9]00)").containsMatchIn(norm)) s = s or RunStyle.BOLD
        if (Regex("font-style\\s*:\\s*(italic|oblique)").containsMatchIn(norm)) s = s or RunStyle.ITALIC
        if (Regex("text-decoration[^;]*underline").containsMatchIn(norm)) s = s or RunStyle.UNDERLINE
        if (Regex("text-decoration[^;]*line-through").containsMatchIn(norm)) s = s or RunStyle.STRIKE
        if (Regex("vertical-align\\s*:\\s*super").containsMatchIn(norm)) s = s or RunStyle.SUP
        if (Regex("vertical-align\\s*:\\s*sub").containsMatchIn(norm)) s = s or RunStyle.SUB
        return s
    }
}
