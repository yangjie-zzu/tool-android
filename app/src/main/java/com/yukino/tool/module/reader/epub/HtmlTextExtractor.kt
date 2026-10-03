package com.yukino.tool.module.reader.epub

import java.io.File
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import com.yukino.tool.module.reader.common.NoteAnchor
import com.yukino.tool.module.reader.common.ParaKind
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.common.Run
import com.yukino.tool.module.reader.common.RunStyle

// XHTML → 段落序列(二期富文本形态;三期增补锚点与脚注)。规则:
//   块级元素边界 = 段落边界;<br> 断段;script/style 等剥离;
//   段内空白规整(连续空白折叠为一,CJK 字符间的粘连空格去除)与 Run 边界记录
//   在同一次遍历完成——规整会改变文本长度,事后映射必错位;
//   内联样式标签与 style 属性合并为 Run(段内坐标),内联标签不是段落边界;
//   <img> 独立成 IMAGE 段落(投影占位 U+FFFC),imageRef 为解压目录内相对路径;
//   ul/ol>li 加 "• "/"N. " 前缀;简单表格逐行"单元格 | 单元格",
//   含跨行跨列或嵌套的复杂表出占位段。
//   锚点: 带 id 的元素,其 id 记为该处产出的首个段落的 anchor(目录 fragment 落点);
//   脚注: noteref(epub:type/class 含 noteref 的 a)角标文本保留进投影并套上标样式,
//   脚注内容元素(epub:type=footnote / class 含 footnote|note / id 被 noteref 引用)
//   不进正文流,文本提入章级 footnotes 表(同文档脚注;跨文档 noteref 降级为普通文本)。
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

    // 解析产物: 段落序列 + 章级脚注内容表(noteId → 纯文本)
    class ExtractResult(val paragraphs: List<Paragraph>, val footnotes: Map<String, String>)

    fun extract(file: File, docDir: String = ""): ExtractResult {
        val doc = Jsoup.parse(file, "UTF-8")
        // <style> 通常在 head(body() 拿不到),从整个文档收集 class 规则
        val cssRules = HashMap<String, Map<String, String>>()
        for (style in doc.select("style")) {
            mergeCssRules(cssRules, parseStyleBlock(style.data()))
        }
        // 六期 A1: 外部 CSS 文件(<link rel="stylesheet">,真实 EPUB 样式的主要载体)。
        // 相对文档文件解析;单层引用不追 import;大小上限 2MB 防病态;缺失/失败宽容跳过
        for (link in doc.select("link")) {
            if (link.attr("rel").trim().lowercase() != "stylesheet") continue
            val href = link.attr("href").trim()
            if (href.isEmpty() || href.startsWith("http", true)) continue
            runCatching {
                val f = File(file.parentFile, percentDecode(href))
                if (f.exists() && f.isFile && f.length() in 1..2_000_000L) {
                    mergeCssRules(cssRules, parseStyleBlock(f.readText()))
                }
            }
        }
        return extract(doc.body(), docDir, cssRules)
    }

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

    fun extract(body: Element, docDir: String = ""): ExtractResult = extract(body, docDir, null)

    fun extract(body: Element, docDir: String = "", cssRulesIn: Map<String, Map<String, String>>?): ExtractResult {
        // 预扫描 noteref 引用的 fragment: id 命中的元素即脚注容器(宽容覆盖无类型标记的脚注)
        val noteRefs = LinkedHashSet<String>()
        for (a in body.select("a[href]")) {
            if (isNoteRef(a)) {
                val frag = stripFragment(a.attr("href")).second
                if (!frag.isNullOrBlank()) noteRefs += frag
            }
        }
        // body 内联的 <style> 也收集(测试入口 parseBodyFragment 场景);外部传入的规则优先合并
        val cssRules = HashMap<String, Map<String, String>>()
        if (cssRulesIn != null) cssRules.putAll(cssRulesIn)
        for (style in body.select("style")) {
            mergeCssRules(cssRules, parseStyleBlock(style.data()))
        }
        val b = Builder(docDir)
        walk(body, b, ArrayDeque(), noteRefs, cssRules)
        b.flush()
        return ExtractResult(b.result(), b.notes)
    }

    // noteref 判定(保守: 仅显式标记;EPUB2 无标记内链不识别以免误伤普通链接)
    private fun isNoteRef(a: Element): Boolean {
        val href = a.attr("href")
        if (!href.startsWith("#")) return false   // 跨文档脚注降级为普通文本
        val type = a.attr("epub:type").lowercase()
        if (type.split(' ').contains("noteref")) return true
        return a.attr("class").lowercase().split(' ').any { it == "noteref" }
    }

    // ---------- 四期 CSS 子集 ----------
    // 范围: text-align(center/right) / text-indent(em) / margin-top/bottom(em)。
    // 来源: <style> 块的单类名选择器 + 元素 style 属性(style 优先);其余属性/选择器忽略。

    // <style> 内容 → 规则表(class → 属性表)。只认选择器恰为单个类名(如 ".center");
    // 逗号列表逐个认;去注释;声明按 ';' 切
    internal fun parseStyleBlock(css: String): Map<String, Map<String, String>> {
        val out = HashMap<String, Map<String, String>>()
        val noComment = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for (rule in noComment.split('}')) {
            val brace = rule.indexOf('{')
            if (brace < 0) continue
            val selectors = rule.substring(0, brace).split(',').map { it.trim() }
            val props = HashMap<String, String>()
            for (decl in rule.substring(brace + 1).split(';')) {
                val i = decl.indexOf(':')
                if (i > 0) {
                    val k = decl.substring(0, i).trim().lowercase()
                    val v = decl.substring(i + 1).trim().lowercase()
                    if (k.isNotEmpty() && v.isNotEmpty()) props[k] = v
                }
            }
            if (props.isEmpty()) continue
            for (sel in selectors) {
                if (sel.length > 1 && sel.startsWith(".") && !sel.substring(1).any { !it.isLetterOrDigit() && it != '-' && it != '_' }) {
                    out.putIfAbsent(sel.substring(1), props)
                }
            }
        }
        return out
    }

    // 合并规则表(后者覆盖前者)
    internal fun mergeCssRules(dst: MutableMap<String, Map<String, String>>, src: Map<String, Map<String, String>>) {
        src.forEach { (k, v) -> dst[k] = dst[k]?.plus(v) ?: v }
    }

    // 元素命中的 class 规则 + style 属性 → 合并属性表(style 优先)
    internal fun propsFor(node: Element, cssRules: Map<String, Map<String, String>>): Map<String, String> {
        var props: Map<String, String>? = null
        for (c in node.attr("class").lowercase().split(' ')) {
            cssRules[c]?.let { props = props?.plus(it) ?: it }
        }
        val inline = node.attr("style").trim()
        if (inline.isNotEmpty()) {
            val inlineProps = HashMap<String, String>()
            for (decl in inline.split(';')) {
                val i = decl.indexOf(':')
                if (i > 0) {
                    inlineProps[decl.substring(0, i).trim().lowercase()] = decl.substring(i + 1).trim().lowercase()
                }
            }
            props = props?.plus(inlineProps) ?: inlineProps
        }
        return props ?: emptyMap()
    }

    // em 值解析("2em"/"1.5em"/"0" → em 数值;px/百分比/其他单位忽略——换算依赖字号,不做)
    private fun emVal(v: String): Float? {
        if (v == "0") return 0f
        val m = Regex("^(-?[\\d.]+)em$").find(v.trim()) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }

    // 排版属性 → 段级布局(仅取子集范围内属性;null = 未设置/跟随全局)。
    // 六期 A3: float:right 降级支持——块标记为右对齐独立块(无文字环绕,正文不避让);
    // float:left 等于默认流向,忽略
    internal fun parseParaLayout(props: Map<String, String>): ParaLayout? {
        var align = 0
        var indentEm: Float? = null
        var aboveEm: Float? = null
        var belowEm: Float? = null
        var floatRight = false
        when (props["text-align"]) {
            "center" -> align = 1
            "right" -> align = 2
        }
        emVal(props["text-indent"] ?: "")?.let { indentEm = it }
        emVal(props["margin-top"] ?: "")?.let { aboveEm = it }
        emVal(props["margin-bottom"] ?: "")?.let { belowEm = it }
        // margin 简写: 单值(四边)/两值(上下 左右)/四值(上 右 下 左)——取上、下
        props["margin"]?.trim()?.split(Regex("\\s+"))?.let { parts ->
            val top = emVal(parts[0])
            if (top != null) {
                if (aboveEm == null) aboveEm = top
                if (belowEm == null) {
                    belowEm = when {
                        parts.size >= 4 -> emVal(parts[2])
                        parts.size == 2 -> emVal(parts[1])
                        else -> top
                    }
                }
            }
        }
        if (props["float"] == "right") floatRight = true
        return if (align == 0 && indentEm == null && aboveEm == null && belowEm == null && !floatRight) null
        else ParaLayout(align, indentEm, aboveEm, belowEm, floatRight)
    }

    // 段级布局(解析产物;四期 CSS 子集 + 六期 float 降级)
    data class ParaLayout(
        val align: Int,
        val indentEm: Float?,
        val aboveEm: Float?,
        val belowEm: Float?,
        val floatRight: Boolean = false
    )

    // ---------- 段缓冲: 规整文本与 Run 边界一体化记录 ----------
    //
    // 流式规则: 空白字符不立即落盘,记 pendingSpace;遇到实字符时决定空格归属——
    //   段首(此前无内容)丢弃;前后都是 CJK 丢弃(HTML 源码换行伪影);否则落一个 ' '。
    // 空格与实字符都按"落盘时刻"的当前样式记入 Run;相邻同样式 Run 天然合为一段。
    // 锚点: openAnchors 为当前打开的带 id 元素(最早优先),段落收口时消费最早者;
    // 角标: noteref 文本落盘区间记入 pendingNotes,随段落收口转段内坐标
    internal class Builder(val docDir: String) {
        private val out = ArrayList<Paragraph>()
        private val sb = StringBuilder()
        private val runs = ArrayList<Run>()
        var curStyle = 0
        private var pendingSpace = false
        val openAnchors = ArrayDeque<String>()
        private val pendingNotes = ArrayList<NoteAnchor>()
        private val pendingInline = ArrayList<com.yukino.tool.module.reader.common.InlineImg>()
        val notes = LinkedHashMap<String, String>()

        // 段级排版上下文(CSS 继承简化: 子元素未设用父值,设了覆盖;离开元素恢复快照)
        var paraAlign = 0
        var paraIndentEm: Float? = null
        var paraAboveEm: Float? = null
        var paraBelowEm: Float? = null
        var pendingHeading = 0

        fun result(): List<Paragraph> = out

        // 快照/恢复排版上下文(元素进出)
        fun snapshotLayout(): Array<Any?> =
            arrayOf(paraAlign, paraIndentEm, paraAboveEm, paraBelowEm)

        fun restoreLayout(s: Array<Any?>) {
            paraAlign = s[0] as Int
            paraIndentEm = s[1] as Float?
            paraAboveEm = s[2] as Float?
            paraBelowEm = s[3] as Float?
        }

        fun applyLayout(l: ParaLayout?) {
            if (l == null) return
            if (l.align != 0) paraAlign = l.align
            // 六期 A3: float:right 降级——浮块整体靠右显示(子段未显式对齐时)
            if (l.floatRight && paraAlign == 0) paraAlign = 2
            if (l.indentEm != null) paraIndentEm = l.indentEm
            if (l.aboveEm != null) paraAboveEm = l.aboveEm
            if (l.belowEm != null) paraBelowEm = l.belowEm
        }

        // CJK 字符(汉字/CJK标点/全角): 与一期 CJK_GLUE 同一区间
        private fun isCjk(c: Char): Boolean =
            c.code in 0x2E80..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF

        private fun openRun() {
            val last = runs.lastOrNull()
            if (last == null || last.style != curStyle) runs += Run(sb.length, sb.length, curStyle)
        }

        // 返回实字符落盘区间(规整可能丢字符: 段首空白/CJK 粘连空格)
        fun appendTextTracked(s: String): Pair<Int, Int> {
            var start = -1
            for (c in s) {
                if (c.isWhitespace()) { pendingSpace = true; continue }
                if (pendingSpace) {
                    pendingSpace = false
                    if (sb.isNotEmpty() && !(isCjk(sb[sb.length - 1]) && isCjk(c))) {
                        openRun()
                        sb.append(' ')
                    }
                }
                if (start < 0) start = sb.length
                openRun()
                sb.append(c)
                runs[runs.lastIndex] = runs.last().copy(end = sb.length)
            }
            return start to sb.length
        }

        fun appendText(s: String) {
            appendTextTracked(s)
        }

        // 段落收口: 全空白段丢弃(空白从未落盘,无需 trim);无有效样式的 Run 退化为空;
        // 锚点消费最早打开者(容器 id 指向容器首段);pendingNotes 转段内坐标
        fun flush() {
            if (sb.isNotBlank()) {
                var rs = runs.filter { it.end > it.start }
                if (rs.all { it.style == 0 }) rs = emptyList()
                val inPara = pendingNotes.filter { it.end > it.start }
                out += Paragraph(
                    sb.toString(), rs,
                    anchor = openAnchors.firstOrNull(),
                    notes = inPara,
                    align = paraAlign,
                    indentEm = paraIndentEm,
                    spaceAboveEm = paraAboveEm,
                    spaceBelowEm = paraBelowEm,
                    heading = pendingHeading,
                    inlineImages = pendingInline.filter { it.start < sb.length }
                )
                if (openAnchors.isNotEmpty()) openAnchors.removeFirst()
            }
            sb.setLength(0)
            runs.clear()
            pendingNotes.clear()
            pendingInline.clear()
            pendingSpace = false
        }

        fun addImage(ref: String) {
            out += Paragraph(
                IMAGE_PLACEHOLDER, emptyList(), ParaKind.IMAGE, imageRef = ref,
                anchor = openAnchors.firstOrNull()?.also { openAnchors.removeFirst() },
                align = paraAlign,
                spaceAboveEm = paraAboveEm,
                spaceBelowEm = paraBelowEm
            )
        }

        fun addPlainPara(text: String) {
            if (text.isNotEmpty()) out += Paragraph(text)
        }

        // 记录脚注内容(id 幂等,首见优先)
        fun addNote(id: String, text: String) {
            val t = collapseNote(text)
            if (t.isNotEmpty()) notes.putIfAbsent(id, t)
        }

        fun pendingNote(start: Int, end: Int, noteId: String) {
            pendingNotes += NoteAnchor(start, end, noteId)
        }

        fun pendingInline(start: Int, ref: String) {
            pendingInline += com.yukino.tool.module.reader.common.InlineImg(start, ref)
        }
    }

    // 脚注内容规整(整段产出无 Run)
    private fun collapseNote(s: String): String {
        val ws = Regex("[\\t\\n\\x0B\\f\\r ]+")
        val cjkGlue = Regex(
            "(?<=[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF]) +(?=[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])"
        )
        return cjkGlue.replace(ws.replace(s, " ").trim(), "")
    }

    // ---------- 树遍历 ----------

    private fun walk(node: Node, b: Builder, counters: ArrayDeque<Int?>, noteIds: Set<String>, cssRules: Map<String, Map<String, String>>) {
        when (node) {
            is TextNode -> b.appendText(node.text())
            is Element -> walkElement(node, b, counters, noteIds, cssRules)
        }
    }

    private fun walkElement(node: Element, b: Builder, counters: ArrayDeque<Int?>, noteIds: Set<String>, cssRules: Map<String, Map<String, String>>) {
        val name = node.tagName().lowercase()
        if (name in SKIP) return

        // 脚注内容容器: 不进正文流,文本提入 notes 表
        val id = node.attr("id").trim()
        val type = node.attr("epub:type").lowercase()
        val cls = node.attr("class").lowercase().split(' ')
        if (name != "a" && ((type.split(' ').contains("footnote")) ||
                cls.any { it == "footnote" || it == "note" } || (id.isNotEmpty() && id in noteIds))
        ) {
            val key = id.ifBlank { "note-${b.notes.size}" }
            b.addNote(key, node.text())
            return
        }

        // noteref 角标: 文本保留进投影(上标样式),区间记为脚注锚点;不 walk 子节点。
        // 五期: 角标内容为图片(Calibre/duokan 生态)时,投影落 U+FFFC 占位并记行内图片(可点)
        if (name == "a" && isNoteRef(node)) {
            val frag = stripFragment(node.attr("href")).second
            if (frag.isNullOrBlank()) return
            val label = node.text()
            if (label.isNotBlank()) {
                val saved = b.curStyle
                b.curStyle = saved or RunStyle.SUP
                val (s, e) = b.appendTextTracked(label)
                b.curStyle = saved
                if (e > s) b.pendingNote(s, e, frag)
            } else {
                val img = node.selectFirst("img")
                val src = img?.attr("src")?.trim() ?: ""
                if (src.isNotEmpty() && !src.startsWith("http", true)) {
                    val ref = resolveHref(b.docDir, percentDecode(src))
                    if (ref.isNotBlank()) {
                        val (s, e) = b.appendTextTracked(HtmlTextExtractor.IMAGE_PLACEHOLDER)
                        if (e > s) {
                            b.pendingNote(s, e, frag)
                            b.pendingInline(s, ref)
                        }
                    }
                }
            }
            return
        }

        // style 属性近似解析(CSS 继承语义的简化: 作用于本元素的全部子孙文本)
        val attrStyle = parseStyleAttr(node.attr("style"))
        val saved = b.curStyle
        if (attrStyle != 0) b.curStyle = saved or attrStyle
        if (id.isNotEmpty()) b.openAnchors.addLast(id)

        // 四期 CSS 子集: 段级排版属性(class 规则 + style 属性,style 优先;子未设用父,离开恢复)
        val savedLayout = b.snapshotLayout()
        b.applyLayout(parseParaLayout(propsFor(node, cssRules)))

        try {
            when {
                name == "br" -> b.flush()
                name == "img" -> { b.flush(); emitImage(node, b) }
                name == "table" -> { b.flush(); emitTable(node, b) }
                name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> {
                    // 标题段: 记 heading 级别(h2 拆章依据)
                    b.flush()
                    b.pendingHeading = name[1] - '0'
                    for (c in node.childNodes()) walk(c, b, counters, noteIds, cssRules)
                    b.flush()
                    b.pendingHeading = 0
                }
                INLINE_STYLE.containsKey(name) -> {
                    b.curStyle = saved or attrStyle or (INLINE_STYLE[name] ?: 0)
                    for (c in node.childNodes()) walk(c, b, counters, noteIds, cssRules)
                }
                name == "ol" -> { counters.addLast(0); walkBlock(node, b, counters, noteIds, cssRules); counters.removeLast() }
                name == "ul" -> { counters.addLast(null); walkBlock(node, b, counters, noteIds, cssRules); counters.removeLast() }
                name == "li" -> {
                    b.flush()   // 结束上一段;前缀之后的内容同段,不再 flush(前缀与首行同段落)
                    val n = counters.lastOrNull()
                    if (n != null) { counters[counters.lastIndex] = n + 1; b.appendText("${n + 1}. ") }
                    else b.appendText("• ")
                    for (c in node.childNodes()) walk(c, b, counters, noteIds, cssRules)
                    b.flush()
                }
                name in BLOCK -> {
                    b.flush()
                    for (c in node.childNodes()) walk(c, b, counters, noteIds, cssRules)
                    b.flush()
                }
                else -> for (c in node.childNodes()) walk(c, b, counters, noteIds, cssRules)
            }
        } finally {
            if (id.isNotEmpty()) b.openAnchors.removeId(id)
            b.curStyle = saved
            b.restoreLayout(savedLayout)
        }
    }

    // 移除最近打开的同名 id(元素离开;同名 id 重复嵌套罕见)
    private fun ArrayDeque<String>.removeId(id: String) {
        for (i in indices.reversed()) if (this[i] == id) { removeAt(i); return }
    }

    // 块级元素: 前后都是段落边界
    private fun walkBlock(node: Element, b: Builder, counters: ArrayDeque<Int?>, noteIds: Set<String>, cssRules: Map<String, Map<String, String>>) {
        b.flush()
        for (c in node.childNodes()) walk(c, b, counters, noteIds, cssRules)
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
