package com.yukino.tool.module.reader.epub

import java.io.File
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import com.yukino.tool.module.reader.common.CssLen
import com.yukino.tool.module.reader.common.TableCell
import com.yukino.tool.module.reader.common.TableData
import com.yukino.tool.module.reader.common.BoxStyle
import com.yukino.tool.module.reader.common.EdgeStyle
import com.yukino.tool.module.reader.common.NoteAnchor
import kotlin.math.roundToInt
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
    class ExtractResult(
        val paragraphs: List<Paragraph>,
        val footnotes: Map<String, String>,
        val fonts: Map<String, String> = emptyMap()   // 七期: @font-face family -> 字体文件相对路径
    )

    fun extract(file: File, docDir: String = ""): ExtractResult {
        val doc = Jsoup.parse(file, "UTF-8")
        // <style> 通常在 head(body() 拿不到),从整个文档收集规则与 @font-face
        val cssRules = ArrayList<CssRule>()
        val fontFaces = HashMap<String, String>()
        for (style in doc.select("style")) {
            mergeCssRules(cssRules, parseStyleBlock(style.data()))
            mergeFontFaces(fontFaces, parseFontFaces(style.data()))
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
                    mergeFontFaces(fontFaces, parseFontFaces(f.readText()))
                }
            }
        }
        return extract(doc.body(), docDir, cssRules, fontFaces)
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

    fun extract(body: Element, docDir: String = ""): ExtractResult = extract(body, docDir, null, null)

    fun extract(body: Element, docDir: String = "", cssRulesIn: List<CssRule>?, fontsIn: Map<String, String>? = null): ExtractResult {
        // 预扫描 noteref 引用的 fragment: id 命中的元素即脚注容器(宽容覆盖无类型标记的脚注)
        val noteRefs = LinkedHashSet<String>()
        for (a in body.select("a[href]")) {
            if (isNoteRef(a)) {
                val frag = stripFragment(a.attr("href")).second
                if (!frag.isNullOrBlank()) noteRefs += frag
            }
        }
        // body 内联的 <style> 也收集(测试入口 parseBodyFragment 场景);外部传入的规则优先合并
        val cssRules = ArrayList<CssRule>()
        if (cssRulesIn != null) cssRules.addAll(cssRulesIn)
        for (style in body.select("style")) {
            mergeCssRules(cssRules, parseStyleBlock(style.data()))
        }
        val b = Builder(docDir)
        walk(body, b, ArrayDeque(), noteRefs, cssRules)
        b.flush()
        return ExtractResult(b.result(), b.notes, fontsIn ?: emptyMap())
    }

    // noteref 判定(保守: 仅显式标记;EPUB2 无标记内链不识别以免误伤普通链接)
    private fun isNoteRef(a: Element): Boolean {
        val href = a.attr("href")
        if (!href.startsWith("#")) return false   // 跨文档脚注降级为普通文本
        val type = a.attr("epub:type").lowercase()
        if (type.split(' ').contains("noteref")) return true
        return a.attr("class").lowercase().split(' ').any { it == "noteref" }
    }

    // ---------- CSS 规则(七期起选择器完整化) ----------
    // 支持的选择器形态(书内 CSS 出现过的全部形态):
    //   ".cls"(类) / "tag"(单元素) / "tag.cls"(复合) / "A B"(后代) / "A > B"(子代),
    //   逗号列表逐个认。伪类/属性/id 选择器不认(整条选择器跳过,同规则其余逗号项保留)。
    // 层叠: specificity(元素 < 类 < 复合/链) 升序稳定合并,同 specificity 源序后者覆盖;
    //   style 属性最高(在 propsFor 内最后并入)。

    // 简单选择器: 标签名与/或类名
    data class SimpleSel(val tag: String?, val cls: String?)

    // 完整选择器: 简单选择器链(从祖先到自身) + 子代组合器位置(childAt 含 i 表示
    // chain[i] 与 chain[i+1] 之间是 '>', 否则是后代空格)
    data class RuleSelector(val chain: List<SimpleSel>, val childAt: Set<Int> = emptySet())

    data class CssRule(val sel: RuleSelector, val props: Map<String, String>, val specificity: Int)

    // specificity: 每个含 tag 的简单选择器 +1, 每个含类的 +10(与 CSS 优先级同构的简化)
    private fun specificityOf(chain: List<SimpleSel>): Int =
        chain.sumOf { (if (it.tag != null) 1 else 0) + (if (it.cls != null) 10 else 0) }

    private val SEL_NAME = Regex("^[a-zA-Z][a-zA-Z0-9-]*$")

    // 解析单个简单选择器段("p" / ".cls" / "p.cls");含 #id/[attr]/:pseudo/* 等不支持语法返回 null
    private fun parseSimpleSel(part: String): SimpleSel? {
        var tag: String? = null
        var cls: String? = null
        // 拆 tag 与 .cls: 首段为 tag(若非 '.' 开头), 其后每 '.xxx' 为类
        val pieces = part.split('.')
        for ((i, p) in pieces.withIndex()) {
            if (p.isEmpty()) {
                if (i != 0) return null   // "a..b" 非法; ".cls" 的首空段合法
                continue
            }
            if (!SEL_NAME.matches(p)) return null
            if (i == 0 && !part.startsWith(".")) tag = p.lowercase() else cls = p.lowercase()
        }
        if (tag == null && cls == null) return null
        return SimpleSel(tag, cls)
    }

    // 解析完整选择器("li ul li.c-rules" / "dl.logo-maker > dt");不支持返回 null
    private fun parseSelector(sel: String): RuleSelector? {
        val s = sel.trim()
        if (s.isEmpty()) return null
        val chain = ArrayList<SimpleSel>()
        val childAt = HashSet<Int>()
        // 按 '>' 切分, 段间记录子代组合器
        val childParts = s.split('>')
        for ((ci, cp) in childParts.withIndex()) {
            if (ci > 0) childAt.add(chain.size - 1)   // chain 末元素与下一段之间为 '>'
            for (part in cp.trim().split(Regex("\\s+"))) {
                if (part.isEmpty()) continue
                val simple = parseSimpleSel(part) ?: return null
                chain.add(simple)
            }
        }
        if (chain.isEmpty()) return null
        return RuleSelector(chain, childAt)
    }

    // 声明块("k: v; k2: v2 !important") → 属性表(小写, 剥 !important)
    internal fun parseDeclarations(text: String): Map<String, String> {
        val props = HashMap<String, String>()
        for (decl in text.split(';')) {
            val i = decl.indexOf(':')
            if (i > 0) {
                val k = decl.substring(0, i).trim().lowercase()
                var v = decl.substring(i + 1).trim()
                // 值统一小写(CSS 关键词不敏感),但 url(...) 内路径大小写敏感(EPUB 解压路径)原样保留
                val lower = v.lowercase()
                v = if (lower.startsWith("url(")) "url(" + v.substring(4) else lower
                if (v.endsWith("!important")) v = v.removeSuffix("!important").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) props[k] = v
            }
        }
        return props
    }

    // <style> 内容 → 规则列表。去注释;声明按 ';' 切;@font-face 等非选择器规则
    // 因 '@' 不合选择器语法自然跳过(字体映射由批次三单独解析)
    internal fun parseStyleBlock(css: String): List<CssRule> {
        val out = ArrayList<CssRule>()
        val noComment = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for (rule in noComment.split('}')) {
            val brace = rule.indexOf('{')
            if (brace < 0) continue
            val props = parseDeclarations(rule.substring(brace + 1))
            if (props.isEmpty()) continue
            for (selText in rule.substring(0, brace).split(',')) {
                val sel = parseSelector(selText) ?: continue
                out += CssRule(sel, props, specificityOf(sel.chain))
            }
        }
        return out
    }

    // 合并规则列表(保序追加)
    internal fun mergeCssRules(dst: MutableList<CssRule>, src: List<CssRule>) {
        dst.addAll(src)
    }

    // 简单选择器是否命中元素
    private fun matchSimple(el: Element, s: SimpleSel): Boolean {
        if (s.tag != null && el.tagName().lowercase() != s.tag) return false
        if (s.cls != null && el.classNames().none { it.lowercase() == s.cls }) return false
        return true
    }

    // 选择器命中判定: 自身须命中链尾, 向上逐级找祖先命中链前项(子代组合器限父级)。
    // 后代链用贪心(每级任取一命中祖先), 不做完整回溯——降级渲染对极少数误报宽容
    private fun selectorMatches(el: Element, sel: RuleSelector): Boolean {
        if (!matchSimple(el, sel.chain.last())) return false
        var cur = el
        for (i in sel.chain.size - 2 downTo 0) {
            if (i in sel.childAt) {
                val p = cur.parent() as? Element ?: return false
                if (!matchSimple(p, sel.chain[i])) return false
                cur = p
            } else {
                var p = cur.parent() as? Element
                var found = false
                while (p != null) {
                    if (matchSimple(p, sel.chain[i])) { found = true; break }
                    p = p.parent() as? Element
                }
                if (!found) return false
            }
        }
        return true
    }

    // 元素命中的全部规则按 specificity 稳定升序合并,再并 style 属性(最高)
    internal fun propsFor(node: Element, cssRules: List<CssRule>): Map<String, String> {
        var props: Map<String, String>? = null
        cssRules.asSequence()
            .filter { selectorMatches(node, it.sel) }
            .sortedBy { it.specificity }   // stable: 同优先级保持源序
            .forEach { props = props?.plus(it.props) ?: it.props }
        val inline = node.attr("style").trim()
        if (inline.isNotEmpty()) {
            val inlineProps = parseDeclarations(inline)
            if (inlineProps.isNotEmpty()) props = props?.plus(inlineProps) ?: inlineProps
        }
        return props ?: emptyMap()
    }

    // em 值解析("2em"/"1.5em"/"0" → em 数值;其他单位忽略)——首行缩进仍为 em-only
    private fun emVal(v: String): Float? {
        if (v == "0") return 0f
        val m = Regex("^(-?[\\d.]+)em$").find(v.trim()) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }

    // 排版属性 → 段级布局(仅取子集范围内属性;null = 未设置/跟随全局)。
    // 六期 A3: float:right 降级支持——块标记为右对齐独立块(无文字环绕,正文不避让);
    // float:left 等于默认流向,忽略。
    // 七期: 长度统一 em/px(÷16)/%(相对可用宽);margin-left/right + 简写左右分量 +
    // padding 四向; 显式 text-align left(3)/justify(4); width 定宽; line-height 段级行距
    internal fun parseParaLayout(props: Map<String, String>): ParaLayout? {
        var align = 0
        var indentEm: Float? = null
        var above: CssLen? = null
        var below: CssLen? = null
        var left: CssLen? = null
        var right: CssLen? = null
        var width: CssLen? = null
        var lineMult: Float? = null
        var floatRight = false
        var floatLeft = false
        var widthAlign = 0
        var breakAll = false
        when (props["text-align"]) {
            "center" -> align = 1
            "right" -> align = 2
            "left" -> align = 3
            "justify" -> align = 4
        }
        emVal(props["text-indent"] ?: "")?.let { indentEm = it }
        CssLen.parse(props["margin-top"] ?: "")?.let { above = it }
        CssLen.parse(props["margin-bottom"] ?: "")?.let { below = it }
        CssLen.parse(props["margin-left"] ?: "")?.let { left = it }
        CssLen.parse(props["margin-right"] ?: "")?.let { right = it }
        CssLen.parse(props["width"] ?: "")?.let { width = it }
        // padding: 上下折段前段后,左右折整段缩进;简写四值展开(上 右 下 左)
        CssLen.parse(props["padding-top"] ?: "")?.let { above = mergeLen(above, it) }
        CssLen.parse(props["padding-bottom"] ?: "")?.let { below = mergeLen(below, it) }
        CssLen.parse(props["padding-left"] ?: "")?.let { left = mergeLen(left, it) }
        CssLen.parse(props["padding-right"] ?: "")?.let { right = mergeLen(right, it) }
        props["padding"]?.let { v ->
            val parts = expand4(v)
            CssLen.parse(parts[0])?.let { above = mergeLen(above, it) }
            CssLen.parse(parts[2])?.let { below = mergeLen(below, it) }
            CssLen.parse(parts[3])?.let { left = mergeLen(left, it) }
            CssLen.parse(parts[1])?.let { right = mergeLen(right, it) }
        }
        props["line-height"]?.let { lineMult = lineHeightVal(it) }
        // margin 简写: 单值(四边)/两值(上下 左右)/三值(上 左右 下)/四值(上 右 下 左);
        // 定宽时 margin auto 按 CSS 语义定位: 双 auto=居中, 左 auto=贴右, 右 auto=贴左
        props["margin"]?.trim()?.split(Regex("\\s+"))?.let { parts ->
            fun at(i: Int) = if (i < parts.size) parts[i] else ""
            CssLen.parse(at(0))?.let { if (above == null) above = it }
            val bottomRaw = when {
                parts.size >= 3 -> at(2)
                parts.size == 2 -> at(1)
                else -> at(0)
            }
            CssLen.parse(bottomRaw)?.let { if (below == null) below = it }
            val lr = when {
                parts.size >= 4 -> listOf(at(3), at(1))
                parts.size >= 2 -> listOf(at(1), at(1))
                else -> listOf(at(0), at(0))
            }
            var autoL = false
            var autoR = false
            if (left == null) {
                if (lr[0] == "auto") autoL = true else CssLen.parse(lr[0])?.let { left = it }
            }
            if (right == null) {
                if (lr[1] == "auto") autoR = true else CssLen.parse(lr[1])?.let { right = it }
            }
            if (width != null) {
                widthAlign = when {
                    autoL && autoR -> 1
                    autoL -> 2          // 左 auto: 盒推到右缘
                    autoR -> 3          // 右 auto: 盒靠左缘
                    else -> widthAlign
                }
            }
        }
        // 分边长属性 margin-left/right: auto 同样是定位语义(单边 auto + 定宽)
        if (props["margin-left"] == "auto" && width != null) widthAlign = 2
        if (props["margin-right"] == "auto" && width != null) widthAlign = 3
        val wb = props["word-break"]
        if (wb == "break-all") breakAll = true
        if (props["word-wrap"] == "break-word" || props["overflow-wrap"] == "break-word") breakAll = true
        if (props["float"] == "right") floatRight = true
        if (props["float"] == "left") floatLeft = true
        val any = align != 0 || indentEm != null || above != null || below != null ||
            left != null || right != null || width != null || lineMult != null || floatRight || floatLeft || breakAll
        return if (!any) null
        else ParaLayout(align, indentEm, above, below, left, right, width, lineMult, floatRight, widthAlign, floatLeft, breakAll)
    }

    // 已有值保留(先到先得,style 属性在 propsFor 已覆盖同 key),避免简写反向覆盖长属性
    private fun mergeLen(cur: CssLen?, add: CssLen): CssLen = cur ?: add

    // line-height → 倍率: "1.3em"/"1.2"/"120%" 均为 1.2/1.3 类倍率;0% 之类的 0 值钳到下限
    internal fun lineHeightVal(v: String): Float? {
        val s = v.trim()
        (s == "0").let { if (it) return 0f }
        Regex("^([\\d.]+)em$").find(s)?.let { return it.groupValues[1].toFloatOrNull() }
        Regex("^([\\d.]+)%$").find(s)?.let { return it.groupValues[1].toFloatOrNull()?.div(100f) }
        Regex("^([\\d.]+)$").find(s)?.let { return it.groupValues[1].toFloatOrNull() }
        return null
    }

    // ---------- 七期: 颜色 / 边框 / 盒样式 ----------

    // CSS 颜色 → ARGB Long。支持 #RGB/#RGBA/#RRGGBB/#RRGGBBAA、rgb()/rgba()(0-255 或 %)、
    // 常用命名色;认不出返回 null(属性忽略)
    internal fun parseColor(raw: String): Long? {
        val v = raw.trim().lowercase()
        if (v.startsWith("#")) {
            val h = v.substring(1)
            return when (h.length) {
                3 -> hex4(h[0], h[0], h[1], h[1], h[2], h[2], 'f', 'f')
                4 -> hex4(h[0], h[0], h[1], h[1], h[2], h[2], h[3], h[3])
                6 -> hex4(h[0], h[1], h[2], h[3], h[4], h[5], 'f', 'f')
                8 -> hex4(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7])
                else -> null
            }
        }
        val m = Regex("^rgba?\\(([^)]+)\\)$").find(v)
        if (m != null) {
            val parts = m.groupValues[1].split(',').map { it.trim() }
            if (parts.size < 3) return null
            fun ch(s: String): Int? = when {
                s.endsWith("%") -> s.dropLast(1).toFloatOrNull()?.let { (it * 255 / 100).roundToInt() }
                else -> s.toFloatOrNull()?.roundToInt()
            }?.coerceIn(0, 255)
            val r = ch(parts[0]) ?: return null
            val g = ch(parts[1]) ?: return null
            val b = ch(parts[2]) ?: return null
            val a = if (parts.size >= 4) {
                val s = parts[3]
                (if (s.endsWith("%")) s.dropLast(1).toFloatOrNull()?.times(2.55f) else s.toFloatOrNull()?.times(255f))
                    ?.roundToInt()?.coerceIn(0, 255) ?: return null
            } else 255
            return (a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
        }
        return NAMED_COLORS[v]
    }

    private fun hex4(r1: Char, r2: Char, g1: Char, g2: Char, b1: Char, b2: Char, a1: Char, a2: Char): Long? {
        fun h(c1: Char, c2: Char): Int? {
            val hi = c1.digitToIntOrNull(16) ?: return null
            val lo = c2.digitToIntOrNull(16) ?: return null
            return (hi shl 4) or lo
        }
        val r = h(r1, r2) ?: return null
        val g = h(g1, g2) ?: return null
        val b = h(b1, b2) ?: return null
        val a = h(a1, a2) ?: return null
        return (a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    private val NAMED_COLORS = mapOf(
        "white" to 0xFFFFFFFFL, "black" to 0xFF000000L, "red" to 0xFFFF0000L, "green" to 0xFF008000L,
        "blue" to 0xFF0000FFL, "gray" to 0xFF808080L, "grey" to 0xFF808080L, "silver" to 0xFFC0C0C0L,
        "yellow" to 0xFFFFFF00L, "orange" to 0xFFFFA500L, "pink" to 0xFFFFC0CBL, "purple" to 0xFF800080L,
        "navy" to 0xFF000080L, "teal" to 0xFF008080L, "olive" to 0xFF808000L, "lime" to 0xFF00FF00L,
        "aqua" to 0xFF00FFFFL, "cyan" to 0xFF00FFFFL, "fuchsia" to 0xFFFF00FFL, "magenta" to 0xFFFF00FFL,
        "maroon" to 0xFF800000L, "transparent" to 0x00000000L
    )

    // border 系属性 → 四边样式(上右下左)。归并顺序(后者覆盖):
    // border 简写 → border-style/color/width 多值展开 → 分边简写(border-left: ...) → 分边单属性(border-left-style: ...)
    internal fun parseEdges(props: Map<String, String>): List<EdgeStyle> {
        val edges = Array(4) { EdgeStyle(0f, 0, 0xFF000000L) }
        props["border"]?.let { v ->
            val decl = parseBorderDecl(v)
            for (i in 0..3) edges[i] = decl ?: EdgeStyle(0f, 0, 0xFF000000L)
        }
        props["border-style"]?.let { expand4(it).forEachIndexed { i, s -> edges[i] = edges[i].copy(style = borderStyleVal(s)) } }
        props["border-color"]?.let { expand4(it).forEachIndexed { i, s -> parseColor(s)?.let { c -> edges[i] = edges[i].copy(color = c) } } }
        props["border-width"]?.let { expand4(it).forEachIndexed { i, s -> borderWidthEm(s)?.let { w -> edges[i] = edges[i].copy(widthEm = w) } } }
        for ((k, i) in listOf("border-top" to 0, "border-right" to 1, "border-bottom" to 2, "border-left" to 3)) {
            props[k]?.let { v -> parseBorderDecl(v)?.let { edges[i] = it } }
        }
        for ((k, i) in listOf(
            "border-top-style" to 0, "border-right-style" to 1, "border-bottom-style" to 2, "border-left-style" to 3,
            "border-top-color" to 0, "border-right-color" to 1, "border-bottom-color" to 2, "border-left-color" to 3,
            "border-top-width" to 0, "border-right-width" to 1, "border-bottom-width" to 2, "border-left-width" to 3
        )) {
            val v = props[k] ?: continue
            edges[i] = when {
                k.endsWith("-style") -> edges[i].copy(style = borderStyleVal(v))
                k.endsWith("-color") -> parseColor(v)?.let { edges[i].copy(color = it) } ?: edges[i]
                else -> borderWidthEm(v)?.let { edges[i].copy(widthEm = it) } ?: edges[i]
            }
        }
        return edges.toList()
    }

    // CSS 多值展开(1 值=四边,2 值=上下 左右,3 值=上 左右 下,4 值=上右下左)
    private fun expand4(v: String): List<String> {
        val parts = v.trim().split(Regex("\\s+"))
        return when (parts.size) {
            1 -> listOf(parts[0], parts[0], parts[0], parts[0])
            2 -> listOf(parts[0], parts[1], parts[0], parts[1])
            3 -> listOf(parts[0], parts[1], parts[2], parts[1])
            else -> parts.take(4)
        }
    }

    // border 简写单声明("2px solid #000" / "dotted 3px #0072E3" / "thick #FF0080 solid" / "0" / "none")
    // → 边样式;含 none/hidden(或仅 0) → 无边
    private fun parseBorderDecl(v: String): EdgeStyle? {
        val s = v.trim()
        if (s.isEmpty()) return null
        if (s == "0" || s == "none" || s == "hidden") return EdgeStyle(0f, 0, 0xFF000000L)
        var w = 3f / 16f   // CSS 缺省 medium
        var style = 0
        var color = 0xFF000000L
        for (tok in s.split(Regex("\\s+"))) {
            val t = tok.lowercase()
            val bw = borderWidthEm(t)
            if (bw != null) { w = bw; continue }
            val bs = borderStyleVal(t)
            if (bs > 0) { style = bs; continue }
            val c = parseColor(t)
            if (c != null) { color = c; continue }
            // 认不出的 token(如 rgb 带空格被拆坏)宽容跳过
        }
        return EdgeStyle(w, style, color)
    }

    private fun borderStyleVal(v: String): Int = when (v.trim().lowercase()) {
        "solid" -> 1; "dotted" -> 2; "dashed" -> 3; "double" -> 4
        "ridge" -> 5; "groove" -> 6; "inset" -> 7; "outset" -> 8
        else -> 0
    }

    // 边框宽 → em("2px"/"0.1em"/"thin"/"medium"/"thick";0 也有效)
    private fun borderWidthEm(v: String): Float? = when (v.trim().lowercase()) {
        "thin" -> 1f / 16f
        "medium" -> 3f / 16f
        "thick" -> 5f / 16f
        else -> CssLen.parse(v)?.let { if (it.pct) null else it.v }
    }

    // url(...) 值提取(背景图)
    private fun parseUrlValue(v: String): String? {
        val m = Regex("url\\(([^)]+)\\)").find(v) ?: return null
        return m.groupValues[1].trim().trim('"', '\'').takeIf { it.isNotEmpty() }
    }

    // 排版属性 → 盒样式(底色/背景图/圆角/阴影/内边距/四边边框)。
    // 只带视觉的元素生成;全空返回 null(不进盒组)
    internal fun parseBoxStyle(props: Map<String, String>, docDir: String): BoxStyle? {
        val bg = props["background-color"]?.let { parseColor(it) }
            ?.takeIf { it != 0L }   // transparent 不当底色
        var bgImage: String? = null
        for (key in listOf("background-image", "background")) {
            if (bgImage != null) break
            props[key]?.let { v ->
                parseUrlValue(v)?.let { u ->
                    bgImage = resolveHref(docDir, percentDecode(u)).ifBlank { null }
                }
            }
        }
        val radius = props["border-radius"]?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.let { CssLen.parse(it) }
        // 批次四c: 固定高;批次四d: transform: rotate(Ndeg)
        val heightCss = props["height"]?.let { CssLen.parse(it) }?.takeIf { it.v > 0 }
        // 批次四修复: 盒自身 width 与 margin(auto 定位语义)
        val boxWidth = props["width"]?.let { CssLen.parse(it) }?.takeIf { it.v > 0 }
        var boxMarginAuto = 0
        var boxMarginLeft: CssLen? = props["margin-left"]?.let { CssLen.parse(it) }
        var boxMarginRight: CssLen? = props["margin-right"]?.let { CssLen.parse(it) }
        // margin 简写: 四值展开取左右分量, auto 判定定位语义(双 auto 居中/左 auto 贴右/右 auto 贴左)
        props["margin"]?.trim()?.split(Regex("\\s+"))?.let { parts ->
            fun at(i: Int) = if (i < parts.size) parts[i] else ""
            val lr = when {
                parts.size >= 4 -> listOf(at(3), at(1))
                parts.size >= 2 -> listOf(at(1), at(1))
                else -> listOf(at(0), at(0))
            }
            if (boxMarginLeft == null) {
                if (lr[0] == "auto") boxMarginAuto = 2 else boxMarginLeft = CssLen.parse(lr[0])
            }
            if (boxMarginRight == null) {
                if (lr[1] == "auto") {
                    if (boxMarginAuto == 0) boxMarginAuto = 3
                } else if (boxMarginRight == null) boxMarginRight = CssLen.parse(lr[1])
            }
            // 双 auto = 居中
            if (lr[0] == "auto" && lr[1] == "auto") boxMarginAuto = 1
        }
        val rotateDeg = props["transform"]?.let { tf ->
            Regex("rotate\\((-?[\\d.]+)deg\\)").find(tf)?.groupValues?.get(1)?.toFloatOrNull()
        }
        val shadow = props["box-shadow"]?.let { it.trim().lowercase() != "none" } == true
        fun padOf(key: String): Float? = CssLen.parse(props[key] ?: "")?.let { if (it.pct) null else it.v }
        var padTop = padOf("padding-top") ?: 0f
        var padBottom = padOf("padding-bottom") ?: 0f
        var padLeft = padOf("padding-left") ?: 0f
        var padRight = padOf("padding-right") ?: 0f
        props["padding"]?.let { v ->
            val parts = expand4(v)
            listOf(
                0 to { x: Float -> padTop = x },
                1 to { x: Float -> padRight = x },
                2 to { x: Float -> padBottom = x },
                3 to { x: Float -> padLeft = x }
            ).forEach { (idx, set) -> CssLen.parse(parts[idx])?.let { if (!it.pct) set(it.v) } }
        }
        val edges = parseEdges(props)
        val hasEdges = edges.any { it.widthEm > 0f && it.style > 0 }
        if (bg == null && bgImage == null && radius == null && !shadow && !hasEdges &&
            padTop == 0f && padBottom == 0f && padLeft == 0f && padRight == 0f &&
            heightCss == null && rotateDeg == null && boxWidth == null && boxMarginAuto == 0
        ) return null
        return BoxStyle(
            bg, bgImage, radius, shadow, heightCss, rotateDeg,
            boxWidth, boxMarginAuto, boxMarginLeft, boxMarginRight,
            padTop, padBottom, padLeft, padRight, edges
        )
    }

    // 段级布局(解析产物;七期起含左右缩进/定宽/行距/显式对齐)
    data class ParaLayout(
        val align: Int,
        val indentEm: Float?,
        val aboveEm: CssLen?,
        val belowEm: CssLen?,
        val leftEm: CssLen? = null,
        val rightEm: CssLen? = null,
        val widthEm: CssLen? = null,
        val lineMult: Float? = null,
        val floatRight: Boolean = false,
        val widthAlign: Int = 0,
        val floatLeft: Boolean = false,
        val breakAll: Boolean = false
    )

    // ---------- 段缓冲: 规整文本与 Run 边界一体化记录 ----------
    //
    // 流式规则: 空白字符不立即落盘,记 pendingSpace;遇到实字符时决定空格归属——
    //   段首(此前无内容)丢弃;前后都是 CJK 丢弃(HTML 源码换行伪影);否则落一个 ' '。
    // 空格与实字符都按"落盘时刻"的当前样式记入 Run;相邻同样式 Run 天然合为一段。
    // 锚点: openAnchors 为当前打开的带 id 元素(最早优先),段落收口时消费最早者;
    // 角标: noteref 文本落盘区间记入 pendingNotes,随段落收口转段内坐标
    internal class Builder(val docDir: String, fontsIn: Map<String, String>? = null) {

        // 七期: 字体表(family → 相对路径,章文件顶层持久化)与 family → 下标映射。
        // 预收集的 @font-face 先登记,walk 中新见 family 动态追加(兜底)
        val fonts = LinkedHashMap<String, String>()
        private val fontIds = HashMap<String, Int>()

        init {
            fontsIn?.forEach { (k, v) -> registerFont(k.lowercase(), v) }
        }

        private fun registerFont(family: String, path: String): Int {
            fontIds[family]?.let { return it }
            val id = fonts.size
            fonts[family] = path
            fontIds[family] = id
            return id
        }

        fun fontIdOf(family: String?): Int? {
            family ?: return null
            return fontIds[family.lowercase()] ?: registerFont(family.lowercase(), "")
        }
        private val out = ArrayList<Paragraph>()
        private val sb = StringBuilder()
        private val runs = ArrayList<Run>()

        // 当前 Run 装饰上下文(七期批次三: 样式位+字号倍率+颜色+阴影+字体下标)
        var cur: RunCtx = RunCtx()
        private var pendingSpace = false
        val openAnchors = ArrayDeque<String>()
        private val pendingNotes = ArrayList<NoteAnchor>()
        private val pendingInline = ArrayList<com.yukino.tool.module.reader.common.InlineImg>()
        val notes = LinkedHashMap<String, String>()

        // 盒上下文栈: 带盒样式的块元素覆盖期间,其覆盖的段落归属该盒(最内层优先)
        val boxStack = ArrayDeque<com.yukino.tool.module.reader.common.BoxStyle>()

        fun result(): List<Paragraph> = out

        // 当前段缓冲写入位置(ruby 等需要记录基文本区间)
        fun cursor(): Int = sb.length

        // 段级排版上下文(CSS 继承简化: 子元素未设用父值,设了覆盖;离开元素恢复快照)
        var paraAlign = 0
        var paraIndentEm: Float? = null
        var paraAboveEm: CssLen? = null
        var paraBelowEm: CssLen? = null
        var paraLeftEm: CssLen? = null
        var paraRightEm: CssLen? = null
        var paraWidthEm: CssLen? = null
        var paraLineMult: Float? = null
        var paraFloatSide = 0
        var paraWidthAlign = 0
        var paraBreakAll = false
        var pendingHeading = 0

        // 快照/恢复排版上下文(元素进出)
        fun snapshotLayout(): Array<Any?> = arrayOf(
            paraAlign, paraIndentEm, paraAboveEm, paraBelowEm,
            paraLeftEm, paraRightEm, paraWidthEm, paraWidthAlign, paraLineMult, paraFloatSide, paraBreakAll
        )

        fun restoreLayout(s: Array<Any?>) {
            paraAlign = s[0] as Int
            paraIndentEm = s[1] as Float?
            paraAboveEm = s[2] as CssLen?
            paraBelowEm = s[3] as CssLen?
            paraLeftEm = s[4] as CssLen?
            paraRightEm = s[5] as CssLen?
            paraWidthEm = s[6] as CssLen?
            paraWidthAlign = s[7] as Int
            paraLineMult = s[8] as Float?
            paraFloatSide = s[9] as Int
            paraBreakAll = s[10] as Boolean
        }

        fun applyLayout(l: ParaLayout?) {
            if (l == null) return
            if (l.align != 0) paraAlign = l.align
            // 六期 A3: float:right 降级——浮块整体靠右显示(子段未显式对齐时)
            if (l.floatRight && paraAlign == 0) paraAlign = 2
            if (l.indentEm != null) paraIndentEm = l.indentEm
            if (l.aboveEm != null) paraAboveEm = l.aboveEm
            if (l.belowEm != null) paraBelowEm = l.belowEm
            if (l.leftEm != null) paraLeftEm = l.leftEm
            if (l.rightEm != null) paraRightEm = l.rightEm
            if (l.widthEm != null) paraWidthEm = l.widthEm
            if (l.widthAlign != 0) paraWidthAlign = l.widthAlign
            if (l.lineMult != null) paraLineMult = l.lineMult
            paraFloatSide = when {
                l.floatRight -> 1
                l.floatLeft -> 2
                else -> paraFloatSide
            }
            if (l.breakAll) paraBreakAll = true
            if (l.widthAlign != 0) paraWidthAlign = l.widthAlign
        }

        // 段落产出时的整段左右缩进: 直接用上下文值——祖先盒/元素的 padding 已在
        // parseParaLayout 折算并经快照恢复继承(多层盒逐层叠加);
        // BoxStyle.padXXX 只供绘制矩形外扩,不再叠加(否则同层双重计算)
        private fun effectiveLeft(): CssLen? = paraLeftEm

        private fun effectiveRight(): CssLen? = paraRightEm

        // CJK 字符(汉字/CJK标点/全角): 与一期 CJK_GLUE 同一区间
        private fun isCjk(c: Char): Boolean =
            c.code in 0x2E80..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF

        private fun openRun() {
            val fid = fontIdOf(cur.font)
            val last = runs.lastOrNull()
            if (last == null || last.style != cur.style || last.sizeEm != cur.sizeEm ||
                last.color != cur.color || last.shadow != cur.shadow || last.fontId != fid
            ) {
                runs += Run(sb.length, sb.length, cur.style, cur.sizeEm, cur.color, cur.shadow, fid)
            }
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
                if (rs.all { it.style == 0 && it.sizeEm == null && it.color == null && !it.shadow && it.fontId == null }) rs = emptyList()
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
                    inlineImages = pendingInline.filter { it.start < sb.length },
                    marginLeftEm = effectiveLeft(),
                    marginRightEm = effectiveRight(),
                    widthEm = paraWidthEm,
                    widthAlign = paraWidthAlign,
                    lineSpacingMult = paraLineMult,
                    boxStyle = boxStack.lastOrNull(),
                    floatSide = paraFloatSide,
                    breakAll = paraBreakAll
                )
                if (openAnchors.isNotEmpty()) openAnchors.removeFirst()
            }
            sb.setLength(0)
            runs.clear()
            pendingNotes.clear()
            pendingInline.clear()
            pendingSpace = false
        }

        fun addTable(td: com.yukino.tool.module.reader.common.TableData) {
            out += Paragraph(
                IMAGE_PLACEHOLDER, emptyList(), ParaKind.TABLE, imageRef = null,
                anchor = openAnchors.firstOrNull()?.also { openAnchors.removeFirst() },
                align = paraAlign,
                spaceAboveEm = paraAboveEm,
                spaceBelowEm = paraBelowEm,
                marginLeftEm = effectiveLeft(),
                marginRightEm = effectiveRight(),
                boxStyle = boxStack.lastOrNull(),
                table = td,
                floatSide = paraFloatSide,
                breakAll = paraBreakAll
            )
        }

        fun addImage(ref: String, width: CssLen? = null) {
            out += Paragraph(
                IMAGE_PLACEHOLDER, emptyList(), ParaKind.IMAGE, imageRef = ref,
                anchor = openAnchors.firstOrNull()?.also { openAnchors.removeFirst() },
                align = paraAlign,
                spaceAboveEm = paraAboveEm,
                spaceBelowEm = paraBelowEm,
                marginLeftEm = effectiveLeft(),
                marginRightEm = effectiveRight(),
                widthEm = width ?: paraWidthEm,
                widthAlign = paraWidthAlign,
                boxStyle = boxStack.lastOrNull(),
                floatSide = paraFloatSide,
                breakAll = paraBreakAll
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

    private fun walk(node: Node, b: Builder, counters: ArrayDeque<Int?>, noteIds: Set<String>, cssRules: List<CssRule>) {
        when (node) {
            is TextNode -> b.appendText(node.text())
            is Element -> walkElement(node, b, counters, noteIds, cssRules)
        }
    }

    private fun walkElement(node: Element, b: Builder, counters: ArrayDeque<Int?>, noteIds: Set<String>, cssRules: List<CssRule>) {
        val name = node.tagName().lowercase()
        if (name in SKIP) return
        // ruby 注音文本(rt 及 rp 括号)不进正文: rt 的内容由 ruby 分支提入脚注表
        if (name == "rt" || name == "rp") return

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
                val saved = b.cur
                b.cur = saved.copy(style = saved.style or RunStyle.SUP)
                val (s, e) = b.appendTextTracked(label)
                b.cur = saved
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

        // CSS 规则(类/元素/复合选择器) + style 属性 → 合并属性表;run 级样式作用于本元素全部子孙
        val props = propsFor(node, cssRules)
        val saved = b.cur
        b.cur = mergeRunCtx(saved, runDecoFromProps(props))
        if (id.isNotEmpty()) b.openAnchors.addLast(id)

        // 段级排版属性(子未设用父,离开恢复)
        val savedLayout = b.snapshotLayout()
        b.applyLayout(parseParaLayout(props))

        // 七期: 盒样式(底色/边框/圆角/阴影/背景图/内边距)——覆盖期间产出的段落归入该盒
        val box = parseBoxStyle(props, b.docDir)
        if (box != null) b.boxStack.addLast(box)

        try {
            when {
                name == "br" -> b.flush()
                name == "img" -> { b.flush(); emitImage(node, b, cssRules) }
                name == "table" -> { b.flush(); emitTable(node, b, cssRules, noteIds) }
                name == "ruby" -> {
                    // 七期: 基文本进正文(行内不断段), rt 音译提入脚注表并在基文本区间挂可点锚点
                    val rt = node.children()
                        .filter { it.tagName().lowercase() == "rt" }
                        .joinToString(" ") { it.text() }.trim()
                    val start = b.cursor()
                    for (c in node.childNodes()) {
                        if (c is Element && c.tagName().lowercase().let { it == "rt" || it == "rp" }) continue
                        walk(c, b, counters, noteIds, cssRules)
                    }
                    val end = b.cursor()
                    if (rt.isNotEmpty() && end > start) {
                        val key = "ruby-" + Integer.toHexString(System.identityHashCode(node))
                        b.addNote(key, rt)
                        b.pendingNote(start, end, key)
                    }
                }
                name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> {
                    // 标题段: 记 heading 级别(h2 拆章依据)
                    b.flush()
                    b.pendingHeading = name[1] - '0'
                    for (c in node.childNodes()) walk(c, b, counters, noteIds, cssRules)
                    b.flush()
                    b.pendingHeading = 0
                }
                INLINE_STYLE.containsKey(name) -> {
                    b.cur = saved.copy(style = saved.style or runDecoFromProps(props).style or (INLINE_STYLE[name] ?: 0))
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
            if (box != null) b.boxStack.removeLast()
            b.cur = saved
            b.restoreLayout(savedLayout)
        }
    }

    // 移除最近打开的同名 id(元素离开;同名 id 重复嵌套罕见)
    private fun ArrayDeque<String>.removeId(id: String) {
        for (i in indices.reversed()) if (this[i] == id) { removeAt(i); return }
    }

    // 块级元素: 前后都是段落边界
    private fun walkBlock(node: Element, b: Builder, counters: ArrayDeque<Int?>, noteIds: Set<String>, cssRules: List<CssRule>) {
        b.flush()
        for (c in node.childNodes()) walk(c, b, counters, noteIds, cssRules)
        b.flush()
    }

    // img: 任何位置独立成图片段(外部 URL/data URI 忽略——外部资源不入正文)。
    // 七期: style/class 的 width 定宽随段落(物化期按目标宽等比缩放)
    private fun emitImage(node: Element, b: Builder, cssRules: List<CssRule>) {
        val src = node.attr("src").trim()
        if (src.isEmpty() || src.startsWith("http", true) || src.startsWith("data:")) return
        val ref = resolveHref(b.docDir, percentDecode(src))
        if (ref.isNotBlank()) {
            val w = CssLen.parse(propsFor(node, cssRules)["width"] ?: "")
            b.addImage(ref, w)
        }
    }

    // 表格(七期批次四): 真渲染数据提取。colspan/rowspan 网格展开(含被占位跳过);
    // 嵌套表与超阈值(>500 格)仍降级占位。单元格内容经子 Builder 提取投影与 runs
    // (段间单空格拼接,runs 平移),格级样式取 class/style(对齐/垂直对齐/底色/边框/th 加粗)
    private fun emitTable(node: Element, b: Builder, cssRules: List<CssRule>, noteIds: Set<String>) {
        if (node.selectFirst("table table") != null) {
            b.addPlainPara(TABLE_PLACEHOLDER)
            return
        }
        val occupied = HashMap<Int, MutableSet<Int>>()   // row -> 被上方 rowspan 占用的列
        val cells = ArrayList<TableCell>()
        val colHints = HashMap<Int, com.yukino.tool.module.reader.common.CssLen>()  // 批次四修复: td width 提示
        var rowCount = 0
        var colCount = 0
        val trs = node.select("tr")
        for ((ri, tr) in trs.withIndex()) {
            var c = 0
            for (cellEl in tr.children()) {
                val tag = cellEl.tagName().lowercase()
                if (tag != "td" && tag != "th") continue
                while (occupied[ri]?.contains(c) == true) c++
                val rs = cellEl.attr("rowspan").toIntOrNull()?.coerceIn(1, 64) ?: 1
                val cs = cellEl.attr("colspan").toIntOrNull()?.coerceIn(1, 64) ?: 1
                for (dr in 0 until rs) occupied.getOrPut(ri + dr) { HashSet() }.also { it.addAll(c until c + cs) }
                val (text, runs) = extractCellContent(cellEl, b, cssRules, noteIds)
                val props = propsFor(cellEl, cssRules)
                // td style/class 的 width 列宽提示(单列格,同列取首次)
                if ((cellEl.attr("colspan").toIntOrNull() ?: 1) <= 1) {
                    com.yukino.tool.module.reader.common.CssLen.parse(props["width"] ?: "")?.let {
                        colHints.putIfAbsent(c, it)
                    }
                }
                if (text.isNotBlank() || tag == "th") {
                    val pl = parseParaLayout(props)
                    val vAlign = when (props["vertical-align"]) {
                        "top" -> 0; "bottom" -> 2; else -> 1
                    }
                    val box = parseBoxStyle(props, b.docDir)
                    cells += TableCell(
                        row = ri, col = c, rowSpan = rs, colSpan = cs,
                        text = text, runs = runs,
                        align = pl?.align ?: 0,
                        vAlign = vAlign,
                        bg = box?.bg,
                        edges = parseEdges(props),
                        header = tag == "th"
                    )
                }
                c += cs
                colCount = maxOf(colCount, c)
                rowCount = maxOf(rowCount, ri + rs)
            }
            rowCount = maxOf(rowCount, ri + 1)
        }
        if (cells.isEmpty() || cells.size > 500) {
            b.addPlainPara(TABLE_PLACEHOLDER)
            return
        }
        val props = propsFor(node, cssRules)
        b.addTable(
            TableData(
                rows = rowCount, cols = colCount, cells = cells,
                collapse = props["border-collapse"]?.trim() != "separate",
                spacingEm = CssLen.parse(props["border-spacing"] ?: "")?.let { if (it.pct) 0f else it.v } ?: 0f,
                colWidths = (0 until colCount).map { colHints[it] }
            )
        )
    }

    // 单元格内容: 子 Builder 独立提取投影文本与 runs(多段以单空格拼接,runs 平移对齐)。
    // 子上下文不带段落级排版(对齐由 TableCell.align 承载)
    private fun extractCellContent(
        el: Element, b: Builder, cssRules: List<CssRule>, noteIds: Set<String>
    ): Pair<String, List<Run>> {
        val sub = Builder(b.docDir)
        for (c in el.childNodes()) walk(c, sub, ArrayDeque(), noteIds, cssRules)
        sub.flush()
        var text = ""
        var off = 0
        val runs = ArrayList<Run>()
        for (p in sub.result()) {
            // 格内图片段(装饰图)不进格文本——U+FFFC 在格内无图片管线,拼入会画出占位框
            if (p.isImage) continue
            if (text.isNotEmpty()) { text += " "; off += 1 }
            for (r in p.runs) runs += r.copy(start = r.start + off, end = r.end + off)
            text += p.text
            off += p.text.length
        }
        // run.fontId 已按子 Builder 自身表(可能为空)分配——单元格内不引用字体,清零防越界
        return text to runs.map { if (it.fontId != null) it.copy(fontId = null) else it }
    }

    // 单元格文本规整(整段产出无 Run,不走 Builder)
    private fun collapse(s: String): String {
        val ws = Regex("[\\t\\n\\x0B\\f\\r ]+")
        val cjkGlue = Regex(
            "(?<=[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF]) +(?=[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])"
        )
        return cjkGlue.replace(ws.replace(s, " ").trim(), "")
    }

    // 内联 style 属性 → 样式位(宽容匹配;保留供测试与外部调用)
    internal fun parseStyleAttr(style: String): Int = runDecoFromProps(parseDeclarations(style)).style

    // 合并属性表(class/元素规则 + style 属性) → Run 装饰。七期批次三: font-size(倍率)/
    // color/text-shadow/font-family 一并提取;class 规则与 style 属性同路
    internal fun runDecoFromProps(props: Map<String, String>): RunCtx {
        var style = 0
        val fw = props["font-weight"]
        if (fw == "bold" || Regex("^[7-9]00$").matches(fw ?: "")) style = style or RunStyle.BOLD
        val fs = props["font-style"]
        if (fs == "italic" || fs == "oblique") style = style or RunStyle.ITALIC
        props["text-decoration"]?.let {
            if ("underline" in it) style = style or RunStyle.UNDERLINE
            if ("line-through" in it) style = style or RunStyle.STRIKE
        }
        when (props["vertical-align"]) {
            "super" -> style = style or RunStyle.SUP
            "sub" -> style = style or RunStyle.SUB
        }
        val size = props["font-size"]?.let { fontSizeEm(it) }
        val color = props["color"]?.let { parseColor(it) }
        val shadow = props["text-shadow"]?.let { it.trim().lowercase() != "none" } == true
        val font = props["font-family"]?.let { fontFamilyName(it) }
        return RunCtx(style, size, color, shadow, font)
    }

    // font-size → 相对字号倍率(em 值/px÷16/百分比/CSS 关键词;larger/smaller 忽略)
    internal fun fontSizeEm(v: String): Float? {
        CssLen.parse(v)?.let { return if (it.pct) it.v / 100f else it.v }
        return when (v.trim().lowercase()) {
            "xx-small" -> 0.583f; "x-small" -> 0.7f; "small" -> 0.8f
            "medium" -> 1f; "large" -> 1.2f; "x-large" -> 1.5f; "xx-large" -> 2f; "xxx-large" -> 2.4f
            else -> null
        }
    }

    // font-family 值取首个族名(去引号;"title", serif → title)
    internal fun fontFamilyName(v: String): String? =
        v.split(',').firstOrNull()?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }

    // 上下文合并: 新装饰的非空字段覆盖(未设字段保持继承值)
    internal fun mergeRunCtx(base: RunCtx, deco: RunCtx): RunCtx = RunCtx(
        style = base.style or deco.style,
        sizeEm = deco.sizeEm ?: base.sizeEm,
        color = deco.color ?: base.color,
        shadow = deco.shadow || base.shadow,
        font = deco.font ?: base.font
    )

    // @font-face 块 → family → 字体文件相对路径(src url)
    internal fun parseFontFaces(css: String): Map<String, String> {
        val out = HashMap<String, String>()
        val noComment = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for (m in Regex("@font-face\\s*\\{([^}]*)\\}").findAll(noComment)) {
            val decls = parseDeclarations(m.groupValues[1])
            val family = decls["font-family"]?.let { fontFamilyName(it) } ?: continue
            val src = decls["src"]?.let { parseUrlValue(it) } ?: continue
            out[family.lowercase()] = src
        }
        return out
    }

    internal fun mergeFontFaces(dst: MutableMap<String, String>, src: Map<String, String>) {
        dst.putAll(src)
    }
}

// Run 装饰上下文(解析期快照/合并的单位);font 为 family 名(落盘时经 Builder 转字体表下标)
internal data class RunCtx(
    val style: Int = 0,
    val sizeEm: Float? = null,
    val color: Long? = null,
    val shadow: Boolean = false,
    val font: String? = null
)
