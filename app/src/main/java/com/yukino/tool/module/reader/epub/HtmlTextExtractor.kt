package com.yukino.tool.module.reader.epub

import java.io.File
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// XHTML → 纯文本段落序列(一期内容形态)。
// 规则: 块级元素边界 = 段落边界;<br> 断段;script/style 等剥离;
// 段内空白规整(连续空白折叠为一,CJK 字符间的粘连空格去除);
// 空段丢弃(段落间距由排版层的段前距表达,不需要空行)。
// 二期的内联样式(Run)/图片在此处扩展为结构化段落,纯文本投影规则不变
object HtmlTextExtractor {

    // 完全剥离的元素(不可见内容/外部资源)
    private val SKIP = hashSetOf("script", "style", "head", "title", "svg", "link", "meta", "iframe", "object", "video", "audio", "canvas", "template")

    // 块级元素: 进入/离开都是段落边界
    private val BLOCK = hashSetOf(
        "p", "div", "section", "article", "blockquote", "li", "ul", "ol",
        "h1", "h2", "h3", "h4", "h5", "h6", "table", "thead", "tbody", "tfoot",
        "tr", "td", "th", "dl", "dt", "dd", "pre", "aside", "figure", "figcaption",
        "header", "footer", "main", "nav", "hr", "center", "form", "address", "caption"
    )

    // CJK 字符区间(汉字/CJK标点/全角): 邻接空格是 HTML 源码换行伪影,清除
    private val CJK_GLUE = Regex(
        "(?<=[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF]) +(?=[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])"
    )
    private val WS = Regex("[\\t\\n\\x0B\\f\\r ]+")

    fun extract(file: File): List<String> = extract(Jsoup.parse(file, "UTF-8").body())

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

    fun extract(body: Element): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        walk(body, sb, out)
        flush(sb, out)
        return postProcess(out)
    }

    private fun walk(node: Node, sb: StringBuilder, out: MutableList<String>) {
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> {
                val name = node.tagName().lowercase()
                if (name in SKIP) return
                if (name == "br") {
                    flush(sb, out)
                    return
                }
                if (name in BLOCK) {
                    flush(sb, out)
                    for (c in node.childNodes()) walk(c, sb, out)
                    flush(sb, out)
                } else {
                    for (c in node.childNodes()) walk(c, sb, out)
                }
            }
        }
    }

    private fun flush(sb: StringBuilder, out: MutableList<String>) {
        if (sb.isNotBlank()) out += sb.toString().trim()
        sb.setLength(0)
    }

    // 段内空白规整 + 空段过滤(剪掉首尾的连续空段由调用方语义保证非空)
    private fun postProcess(paragraphs: List<String>): List<String> =
        paragraphs.map { WS.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
            .map { CJK_GLUE.replace(it, "") }
}
