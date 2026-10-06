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

    // data URI 图片落盘回调: (mime 小写, base64 载荷原文) → 解压根相对 ref;
    // 返回空串 = 拒收(该图忽略)。null(未注入) = data URI 一律忽略(既有行为)
    fun interface DataUriSink {
        fun accept(mime: String, base64: String): String
    }

    // ---------- 混合渲染: 自绘能力边界外的块级降级(docs/epub-hybrid-render-boundary.md) ----------
    //
    // 信号白名单制: 只命中"自绘明确画不了"的信号才降级,默认自绘。信号:
    //   S1 行内元素盒装饰(span 带 border/background,如圆圈章号 sbox1)
    //   S2 嵌套表格 / 超 500 格表格
    //   S3 内嵌 <svg> 标签(非 svg 文件)
    //   S4 position:absolute/fixed、flex/grid 布局块
    //   S5 渐变背景(linear-gradient 等)
    //
    // 管辖归属: 行内/子树信号向上找"最近管辖祖先"——先遇 table 归表格(表格特判分支判定,
    // 果青1 章首装饰表格场景: sbox1 在 td 内,整个 table 降级一张位图,正文 p 不受牵连),
    // 先遇 BLOCK 元素即该块。body 直接子信号无管辖分支,忽略(罕见)。

    // S1 子树扫描跳过的元素: 块级元素的盒样式自绘支持(边界内);img/br 为替换/空元素非行内盒装饰
    private val S1_SCAN_SKIP = BLOCK + hashSetOf(
        "img", "br", "table", "tr", "td", "th", "tbody", "thead", "tfoot", "caption", "ruby", "rt", "rp"
    )

    // 信号元素的管辖块: 信号在表格内(td/tr 均是 BLOCK,但 td 级位图塞不进表格管线)
    // → 归属 table 整体(表格特判分支判定);否则归属最近的 BLOCK 祖先
    private fun ownerBlockOf(el: Element): Element? {
        val tbl = el.parents().firstOrNull { it.tagName().lowercase() == "table" }
        if (tbl != null) return tbl
        return el.parents().firstOrNull { it.tagName().lowercase() in BLOCK }
    }

    // 行内元素盒装饰判定(S1): 有效边框/背景色(非 transparent)/背景图(含渐变)
    private fun inlineBoxDecorated(props: Map<String, String>): Boolean {
        if (props.isEmpty()) return false
        if (parseEdges(props).any { it.widthEm > 0f && it.style > 0 }) return true
        val bg = props["background-color"]?.let { parseColor(it) }
        if (bg != null && bg != 0L) return true
        for (key in listOf("background", "background-image")) {
            val v = props[key] ?: continue
            if (parseUrlValue(v) != null) return true
            if (v.contains("gradient(", ignoreCase = true)) return true
        }
        return false
    }

    // 块级元素降级判定(walkElement 对每个非 table 块级元素调用): 自身 S4/S5 + 自身
    // "大圆角+部分边框"气泡轮廓 + 归属本块的子树 S3/S1
    internal fun needsWebViewBlock(node: Element, cssRules: List<CssRule>): Boolean {
        val props = propsFor(node, cssRules)
        // S4: position:absolute/fixed、flex/grid 布局块
        when (props["position"]?.trim()?.lowercase()) {
            "absolute", "fixed" -> return true
        }
        when (props["display"]?.trim()?.lowercase()) {
            "flex", "inline-flex", "grid", "inline-grid" -> return true
        }
        // S5: 渐变背景
        for (key in listOf("background", "background-image")) {
            if (props[key]?.contains("gradient(", ignoreCase = true) == true) return true
        }
        // 自身大圆角+部分边框(整页气泡/装饰盒): 绘制层椭圆模式下部分边框只能画象限弧
        // (弧端悬空于边中点), 弧段切分与完整形态不一致——降级位图由块渲染管线完整还原;
        // 小圆角(直边/角弧)与均匀四边框自绘正确,不在此列(避免存量装饰段落大面积位图化)
        if (bubbleOutlineBox(props) != null) return true
        if (subtreeSignalsOwnedBy(node, cssRules)) return true
        return false
    }

    // 气泡轮廓盒: 部分边框(四边有/无混杂) + 大圆角(百分比≥50% 必超限入椭圆模式;
    // em 值≥2 在常见版心/盒宽下超限)。命中返回 BoxStyle(仅作判定,复用解析),否则 null
    private fun bubbleOutlineBox(props: Map<String, String>): com.yukino.tool.module.reader.common.BoxStyle? {
        val box = parseBoxStyle(props, "") ?: return null
        if (box.edges.size != 4) return null
        val styles = box.edges.map { it.style }
        if (!(styles.any { it > 0 } && styles.any { it == 0 })) return null   // 需部分边框
        val rad = box.radius ?: return null
        return if ((rad.pct && rad.v >= 50f) || (!rad.pct && rad.v >= 2f)) box else null
    }

    // 表格降级判定(emitTable 入口调用): S2 嵌套/超 500 格 + 表内 S3/S1
    internal fun needsWebViewTable(node: Element, cssRules: List<CssRule>): Boolean {
        if (node.selectFirst("table table") != null) return true
        if (node.select("td,th").size > 500) return true
        return subtreeSignalsOwnedBy(node, cssRules)
    }

    // 子树信号(归属 node 的): S3 内嵌 svg + S1 行内盒装饰
    private fun subtreeSignalsOwnedBy(node: Element, cssRules: List<CssRule>): Boolean {
        for (svg in node.select("svg")) {
            if (ownerBlockOf(svg) === node) return true
        }
        for (el in node.select("*")) {
            if (el.tagName().lowercase() in S1_SCAN_SKIP) continue
            if (!inlineBoxDecorated(propsFor(el, cssRules))) continue
            if (ownerBlockOf(el) === node) return true
        }
        return false
    }

    // 祖先壳: body 到块元素的逐层开标签(cloneNode(false) 语义: 标签名+全部属性)。
    // body 自身属性并入壳首;html/#root 不入壳(渲染 mini HTML 自带 html/body 框架)
    internal fun ancestorShellOf(node: Element): String {
        val chain = ArrayList<Element>()
        var cur: Element? = node.parent() as? Element
        while (cur != null) {
            chain += cur
            cur = cur.parent() as? Element
        }
        chain.reverse()   // [#root/html, body, ..., 直接父] → 外层在前
        val sb = StringBuilder()
        for (el in chain) {
            val tn = el.tagName().lowercase()
            if (tn == "html" || tn == "#root") continue
            sb.append('<').append(el.tagName())
            for (attr in el.attributes()) {
                sb.append(' ').append(attr.key).append("=\"")
                    .append(attr.value.replace("\"", "&quot;")).append('"')
            }
            sb.append('>')
        }
        return sb.toString()
    }

    // 块投影扁平化(与 WebView 几何采集 JS 同一条规则,对拍单测钉死):
    // 文本节点原样;块级子元素边界与 <br> 折空格;连续空白(\t\n\x0B\f\r 空格)压一;
    // 首尾 ASCII 空白裁剪(U+3000 是 CJK 排版实字符,不折叠不裁剪,与浏览器/采集 JS 同语义)。
    // svg/script 等不可见子树跳过(与 SKIP 同集合);UTF-16 code unit 两侧同构
    internal fun flattenBlockText(node: Element): String {
        val sb = StringBuilder()
        // 只遍历子树(与采集 JS 从 body.childNodes 起遍历同构;node 自身是块级入口不折空格)
        for (c in node.childNodes()) appendFlat(c, sb)
        return sb.toString()
            .replace(Regex("[\\t\\n\\x0B\\f\\r ]+"), " ")
            .trim(' ', '\t', '\n', '\u000B', '\u000C', '\r')
    }

    private fun appendFlat(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> {
                val name = node.tagName().lowercase()
                if (name == "br") {
                    sb.append(' ')
                    return
                }
                if (name in SKIP) return
                // 块级边界折空格(连续块折叠为一个),子树继续遍历——块内文本是可见文字
                if (name in BLOCK) sb.append(' ')
                for (c in node.childNodes()) appendFlat(c, sb)
            }
            else -> {}
        }
    }

    // data:URI 拆解: "data:[mime];base64,payload" → (mime, payload);非 base64/形态不符 null
    internal fun parseDataUri(src: String): Pair<String, String>? {
        if (!src.startsWith("data:", true)) return null
        val comma = src.indexOf(',')
        if (comma < 0) return null
        val head = src.substring(5, comma).lowercase()
        val payload = src.substring(comma + 1)
        if (!head.endsWith(";base64") || payload.isBlank()) return null
        val mime = head.removeSuffix(";base64").ifBlank { "application/octet-stream" }
        return mime to payload
    }

    // 解析产物: 段落序列 + 章级脚注内容表(noteId → 纯文本)
    class ExtractResult(
        val paragraphs: List<Paragraph>,
        val footnotes: Map<String, String>,
        val fonts: Map<String, String> = emptyMap(),   // 七期: @font-face family -> 字体文件相对路径
        val cssHrefs: List<String> = emptyList(),      // 混合渲染: head 外部样式 href 原样(块渲染 mini HTML 引用)
        val cssInline: List<String> = emptyList(),     // 混合渲染: <style> 块原文(块渲染 mini HTML 内联)
        val bodyDecor: Boolean = false,                // 页面级背景信号: body 带背景图/色(装饰章判定用)
        val bodyHtml: String = "",                     // 页面级背景章聚合: 整章 body innerHTML(原始结构浏览器同源渲染)
        val bodyShell: String = "",                    // 页面级背景章聚合: body 开标签(类/属性并入块壳)
        val docDir: String = ""                        // 章源文档目录(相对解压根): 聚合块 CSS/图片相对引用的解析基准
    )

    fun extract(file: File, docDir: String = "", dataUriSink: DataUriSink? = null): ExtractResult {
        val doc = Jsoup.parse(file, "UTF-8")
        // <style> 通常在 head(body() 拿不到),从整个文档收集规则与 @font-face
        val cssRules = ArrayList<CssRule>()
        val fontFaces = HashMap<String, String>()
        for (style in doc.select("style")) {
            mergeCssRules(cssRules, parseStyleBlock(style.data()))
            mergeFontFaces(fontFaces, parseFontFaces(style.data()))
        }
        // head 内 <style> 原文收集(body 内的由 extract(body) 统一收,避免重复)
        val cssInline = ArrayList<String>()
        for (style in doc.head().select("style")) {
            if (style.data().isNotBlank()) cssInline += style.data()
        }
        // 六期 A1: 外部 CSS 文件(<link rel="stylesheet">,真实 EPUB 样式的主要载体)。
        // 相对文档文件解析;单层引用不追 import;大小上限 2MB 防病态;缺失/失败宽容跳过
        val cssHrefs = ArrayList<String>()
        for (link in doc.select("link")) {
            if (link.attr("rel").trim().lowercase() != "stylesheet") continue
            val href = link.attr("href").trim()
            if (href.isEmpty() || href.startsWith("http", true)) continue
            cssHrefs += href
            runCatching {
                val f = File(file.parentFile, percentDecode(href))
                if (f.exists() && f.isFile && f.length() in 1..2_000_000L) {
                    mergeCssRules(cssRules, parseStyleBlock(f.readText()))
                    mergeFontFaces(fontFaces, parseFontFaces(f.readText()))
                }
            }
        }
        return extract(doc.body(), docDir, cssRules, fontFaces, dataUriSink, cssHrefs, cssInline)
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

    fun extract(
        body: Element,
        docDir: String = "",
        cssRulesIn: List<CssRule>?,
        fontsIn: Map<String, String>? = null,
        dataUriSink: DataUriSink? = null,
        cssHrefsIn: List<String> = emptyList(),
        cssInlineIn: List<String> = emptyList()
    ): ExtractResult {
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
        val cssInline = ArrayList<String>(cssInlineIn)
        for (style in body.select("style")) {
            mergeCssRules(cssRules, parseStyleBlock(style.data()))
            if (style.data().isNotBlank()) cssInline += style.data()
        }
        // 页面级背景信号: body 自身声明的背景图/背景色(CSS 规则含元素选择器命中 body)
        val bodyProps = propsFor(body, cssRules)
        val bodyDecor = inlineBoxDecorated(bodyProps) ||
            (bodyProps["background-color"]?.let { parseColor(it) ?: 0L } ?: 0L) != 0L
        // 页面级背景章的聚合渲染: 保留整章 body innerHTML 与开标签壳
        val bodyHtml = if (bodyDecor) body.html() else ""
        val bodyShell = if (bodyDecor) {
            val attrs = body.attributes().joinToString(" ") { (k, v) ->
                "$k=\"" + v.replace("&", "&amp;").replace("\"", "&quot;") + "\""
            }
            if (attrs.isBlank()) "<body>" else "<body $attrs>"
        } else ""
        val b = Builder(docDir, null, dataUriSink)
        walk(body, b, ArrayDeque(), noteRefs, cssRules)
        b.flush()
        return ExtractResult(
            b.result(), b.notes, fontsIn ?: emptyMap(), cssHrefsIn, cssInline, bodyDecor,
            bodyHtml, bodyShell, docDir
        )
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
    //   "#id" / "[attr]" / "[attr op value]"(op: = ~= |= ^= $= *=) / "*" 通配 /
    //   多类 ".a.b" / 上述任意组合("p#x.y[z]"),逗号列表逐个认。
    //   伪类(:hover 等)不认(整条选择器跳过,同规则其余逗号项保留)。
    // 层叠: specificity(元素 1 < 类/属性 10 < id 100) 升序稳定合并,同 specificity 源序后者覆盖;
    //   style 属性最高(在 propsFor 内最后并入)。

    // 属性选择器: op 0=存在 1== 2~=(词列表) 3|=(连字号前缀) 4^= 5$= 6*=(子串)
    data class AttrSel(val name: String, val op: Int, val value: String)

    // 简单选择器: 标签名(与/或)类/id/属性;tag=null = 任意标签(含 '*')
    data class SimpleSel(
        val tag: String? = null,
        val classes: Set<String> = emptySet(),
        val id: String? = null,
        val attrs: List<AttrSel> = emptyList()
    )

    // 完整选择器: 简单选择器链(从祖先到自身) + 子代组合器位置(childAt 含 i 表示
    // chain[i] 与 chain[i+1] 之间是 '>', 否则是后代空格)
    data class RuleSelector(val chain: List<SimpleSel>, val childAt: Set<Int> = emptySet())

    data class CssRule(val sel: RuleSelector, val props: Map<String, String>, val specificity: Int)

    // specificity: 每个含 tag 的简单选择器 +1, 每个类/属性 +10, id +100(与 CSS 优先级同构的简化)
    private fun specificityOf(chain: List<SimpleSel>): Int =
        chain.sumOf {
            (if (it.tag != null) 1 else 0) + it.classes.size * 10 + it.attrs.size * 10 +
                (if (it.id != null) 100 else 0)
        }

    // 裸标签名前缀匹配(无 $ 锚定——"div.abs" 需匹配到前缀 "div",锚定会使 tag.cls 复合选择器解析失败)
    private val SEL_NAME = Regex("^[A-Za-z][A-Za-z0-9-]*")
    private val SEL_TOKEN = Regex("^[A-Za-z0-9_-]+")

    // 解析单个简单选择器段("p" / ".cls" / "*"(通配) / "p#x.y[z=v]");含 :pseudo 等不支持语法返回 null
    private fun parseSimpleSel(part: String): SimpleSel? {
        val s = part.trim()
        var tag: String? = null
        var any = false
        val classes = LinkedHashSet<String>()
        var id: String? = null
        val attrs = ArrayList<AttrSel>()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '*' -> { if (tag != null) return null; any = true; i++ }   // 通配: 全空 SimpleSel = 任意元素
                '#' -> {
                    if (id != null) return null
                    val m = SEL_TOKEN.find(s.substring(i + 1)) ?: return null
                    id = m.value
                    i += 1 + m.value.length
                }
                '.' -> {
                    val m = SEL_TOKEN.find(s.substring(i + 1)) ?: return null   // "a..b" 非法
                    classes += m.value.lowercase()
                    i += 1 + m.value.length
                }
                '[' -> {
                    val close = s.indexOf(']', i)
                    if (close < 0) return null
                    attrs += parseAttrSel(s.substring(i + 1, close)) ?: return null
                    i = close + 1
                }
                else -> {
                    // 裸 tag 只能出现在段首;其余位置出现裸字符(如 ":hover" 的 ':')即不认
                    if (tag != null || classes.isNotEmpty() || id != null || attrs.isNotEmpty()) return null
                    val m = SEL_NAME.find(s.substring(i)) ?: return null
                    tag = m.value.lowercase()
                    i += m.value.length
                }
            }
        }
        if (tag == null && !any && classes.isEmpty() && id == null && attrs.isEmpty()) return null
        return SimpleSel(tag, classes, id, attrs)
    }

    // 属性选择器内层("attr" / "attr=v" / "attr~=v"...);值可带单双引号
    private fun parseAttrSel(inner: String): AttrSel? {
        val t = inner.trim()
        if (t.isEmpty()) return null
        val m = Regex("^([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*(?:([~|^$*]?=)(.*))?$").find(t) ?: return null
        val name = m.groupValues[1].lowercase()
        val opStr = m.groupValues[2]
        if (opStr.isEmpty()) return AttrSel(name, 0, "")
        var value = m.groupValues[3].trim()
        if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))) {
            value = value.substring(1, value.length - 1)
        }
        if (value.isEmpty()) return null
        val op = when (opStr) {
            "=" -> 1; "~=" -> 2; "|=" -> 3; "^=" -> 4; "$=" -> 5; "*=" -> 6
            else -> return null
        }
        return AttrSel(name, op, value)
    }

    // 段内按空白拆简单选择器,'[...]' 内部不拆(属性值可含空格)
    private fun splitSelParts(cp: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inAttr = false
        for (c in cp) {
            when {
                c == '[' -> { inAttr = true; sb.append(c) }
                c == ']' -> { inAttr = false; sb.append(c) }
                c.isWhitespace() && !inAttr -> { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
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
            for (part in splitSelParts(cp)) {
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
        if (s.classes.isNotEmpty()) {
            val cls = el.classNames()
            for (c in s.classes) if (cls.none { it.lowercase() == c }) return false
        }
        if (s.id != null && el.attr("id").trim() != s.id) return false
        for (a in s.attrs) if (!attrMatches(el, a)) return false
        return true
    }

    // 属性匹配(HTML 属性名大小写不敏感,线性找同名属性;值匹配区分大小写)
    private fun attrMatches(el: Element, a: AttrSel): Boolean {
        var raw: String? = null
        for (attr in el.attributes()) {
            if (attr.key.lowercase() == a.name) { raw = attr.value; break }
        }
        raw ?: return false
        val v = raw.trim()
        return when (a.op) {
            0 -> true
            1 -> v == a.value
            2 -> v.split(Regex("\\s+")).any { it == a.value }          // 空白分隔词列表
            3 -> v == a.value || v.startsWith(a.value + "-")           // 连字号前缀(lang|=zh)
            4 -> v.startsWith(a.value)
            5 -> v.endsWith(a.value)
            6 -> v.contains(a.value)
            else -> false
        }
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

    // 排版属性 → 段级布局(仅取子集范围内属性;null = 未设置/跟随全局)。
    // 六期 A3: float:right 降级支持——块标记为右对齐独立块(无文字环绕,正文不避让);
    // float:left 等于默认流向,忽略。
    // 七期: 长度统一 em/px(÷16)/%(相对可用宽);margin-left/right + 简写左右分量 +
    // padding 四向; 显式 text-align left(3)/justify(4); width 定宽; line-height 段级行距
    internal fun parseParaLayout(props: Map<String, String>): ParaLayout? {
        var align = 0
        var indentCss: CssLen? = null
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
        // text-indent 全单位(em/px/%,% 相对包含块宽);0 = 显式关闭缩进
        CssLen.parse(props["text-indent"] ?: "")?.let { indentCss = it }
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
            // auto 是定位语义,检测独立于 left/right 是否已有值(padding 折算的缩进
            // 先占了位不能吞掉 margin auto——t-box3 场景: padding:2px + margin-left:auto)
            if (lr[0] == "auto") autoL = true else if (left == null) CssLen.parse(lr[0])?.let { left = it }
            if (lr[1] == "auto") autoR = true else if (right == null) CssLen.parse(lr[1])?.let { right = it }
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
        val any = align != 0 || indentCss != null || above != null || below != null ||
            left != null || right != null || width != null || lineMult != null || floatRight || floatLeft || breakAll
        return if (!any) null
        else ParaLayout(align, indentCss, above, below, left, right, width, lineMult, floatRight, widthAlign, floatLeft, breakAll)
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

    // CSS 命名色全表(CSS Color Module 148 个,含 gray/grey 双拼与 rebeccapurple) + transparent
    private val NAMED_COLORS = mapOf(
        "aliceblue" to 0xFFF0F8FFL, "antiquewhite" to 0xFFFAEBD7L, "aqua" to 0xFF00FFFFL,
        "aquamarine" to 0xFF7FFFD4L, "azure" to 0xFFF0FFFFL, "beige" to 0xFFF5F5DCL,
        "bisque" to 0xFFFFE4C4L, "black" to 0xFF000000L, "blanchedalmond" to 0xFFFFEBCDL,
        "blue" to 0xFF0000FFL, "blueviolet" to 0xFF8A2BE2L, "brown" to 0xFFA52A2AL,
        "burlywood" to 0xFFDEB887L, "cadetblue" to 0xFF5F9EA0L, "chartreuse" to 0xFF7FFF00L,
        "chocolate" to 0xFFD2691EL, "coral" to 0xFFFF7F50L, "cornflowerblue" to 0xFF6495EDL,
        "cornsilk" to 0xFFFFF8DCL, "crimson" to 0xFFDC143CL, "cyan" to 0xFF00FFFFL,
        "darkblue" to 0xFF00008BL, "darkcyan" to 0xFF008B8BL, "darkgoldenrod" to 0xFFB8860BL,
        "darkgray" to 0xFFA9A9A9L, "darkgreen" to 0xFF006400L, "darkgrey" to 0xFFA9A9A9L,
        "darkkhaki" to 0xFFBDB76BL, "darkmagenta" to 0xFF8B008BL, "darkolivegreen" to 0xFF556B2FL,
        "darkorange" to 0xFFFF8C00L, "darkorchid" to 0xFF9932CCL, "darkred" to 0xFF8B0000L,
        "darksalmon" to 0xFFE9967AL, "darkseagreen" to 0xFF8FBC8FL, "darkslateblue" to 0xFF483D8BL,
        "darkslategray" to 0xFF2F4F4FL, "darkslategrey" to 0xFF2F4F4FL, "darkturquoise" to 0xFF00CED1L,
        "darkviolet" to 0xFF9400D3L, "deeppink" to 0xFFFF1493L, "deepskyblue" to 0xFF00BFFFL,
        "dimgray" to 0xFF696969L, "dimgrey" to 0xFF696969L, "dodgerblue" to 0xFF1E90FFL,
        "firebrick" to 0xFFB22222L, "floralwhite" to 0xFFFFFAF0L, "forestgreen" to 0xFF228B22L,
        "fuchsia" to 0xFFFF00FFL, "gainsboro" to 0xFFDCDCDCL, "ghostwhite" to 0xFFF8F8FFL,
        "gold" to 0xFFFFD700L, "goldenrod" to 0xFFDAA520L, "gray" to 0xFF808080L,
        "green" to 0xFF008000L, "greenyellow" to 0xFFADFF2FL, "grey" to 0xFF808080L,
        "honeydew" to 0xFFF0FFF0L, "hotpink" to 0xFFFF69B4L, "indianred" to 0xFFCD5C5CL,
        "indigo" to 0xFF4B0082L, "ivory" to 0xFFFFFFF0L, "khaki" to 0xFFF0E68CL,
        "lavender" to 0xFFE6E6FAL, "lavenderblush" to 0xFFFFF0F5L, "lawngreen" to 0xFF7CFC00L,
        "lemonchiffon" to 0xFFFFFACDL, "lightblue" to 0xFFADD8E6L, "lightcoral" to 0xFFF08080L,
        "lightcyan" to 0xFFE0FFFFL, "lightgoldenrodyellow" to 0xFFFAFAD2L, "lightgray" to 0xFFD3D3D3L,
        "lightgreen" to 0xFF90EE90L, "lightgrey" to 0xFFD3D3D3L, "lightpink" to 0xFFFFB6C1L,
        "lightsalmon" to 0xFFFFA07AL, "lightseagreen" to 0xFF20B2AAL, "lightskyblue" to 0xFF87CEFAL,
        "lightslategray" to 0xFF778899L, "lightslategrey" to 0xFF778899L, "lightsteelblue" to 0xFFB0C4DEL,
        "lightyellow" to 0xFFFFFFE0L, "lime" to 0xFF00FF00L, "limegreen" to 0xFF32CD32L,
        "linen" to 0xFFFAF0E6L, "magenta" to 0xFFFF00FFL, "maroon" to 0xFF800000L,
        "mediumaquamarine" to 0xFF66CDAAL, "mediumblue" to 0xFF0000CDL, "mediumorchid" to 0xFFBA55D3L,
        "mediumpurple" to 0xFF9370DBL, "mediumseagreen" to 0xFF3CB371L, "mediumslateblue" to 0xFF7B68EEL,
        "mediumspringgreen" to 0xFF00FA9AL, "mediumturquoise" to 0xFF48D1CCL, "mediumvioletred" to 0xFFC71585L,
        "midnightblue" to 0xFF191970L, "mintcream" to 0xFFF5FFFAL, "mistyrose" to 0xFFFFE4E1L,
        "moccasin" to 0xFFFFE4B5L, "navajowhite" to 0xFFFFDEADL, "navy" to 0xFF000080L,
        "oldlace" to 0xFFFDF5E6L, "olive" to 0xFF808000L, "olivedrab" to 0xFF6B8E23L,
        "orange" to 0xFFFFA500L, "orangered" to 0xFFFF4500L, "orchid" to 0xFFDA70D6L,
        "palegoldenrod" to 0xFFEEE8AAL, "palegreen" to 0xFF98FB98L, "paleturquoise" to 0xFFAFEEEEL,
        "palevioletred" to 0xFFDB7093L, "papayawhip" to 0xFFFFEFD5L, "peachpuff" to 0xFFFFDAB9L,
        "peru" to 0xFFCD853FL, "pink" to 0xFFFFC0CBL, "plum" to 0xFFDDA0DDL,
        "powderblue" to 0xFFB0E0E6L, "purple" to 0xFF800080L, "rebeccapurple" to 0xFF663399L,
        "red" to 0xFFFF0000L, "rosybrown" to 0xFFBC8F8FL, "royalblue" to 0xFF4169E1L,
        "saddlebrown" to 0xFF8B4513L, "salmon" to 0xFFFA8072L, "sandybrown" to 0xFFF4A460L,
        "seagreen" to 0xFF2E8B57L, "seashell" to 0xFFFFF5EEL, "sienna" to 0xFFA0522DL,
        "silver" to 0xFFC0C0C0L, "skyblue" to 0xFF87CEEBL, "slateblue" to 0xFF6A5ACDL,
        "slategray" to 0xFF708090L, "slategrey" to 0xFF708090L, "snow" to 0xFFFFFAFAL,
        "springgreen" to 0xFF00FF7FL, "steelblue" to 0xFF4682B4L, "tan" to 0xFFD2B48CL,
        "teal" to 0xFF008080L, "thistle" to 0xFFD8BFD8L, "tomato" to 0xFFFF6347L,
        "turquoise" to 0xFF40E0D0L, "violet" to 0xFFEE82EEL, "wheat" to 0xFFF5DEB3L,
        "white" to 0xFFFFFFFFL, "whitesmoke" to 0xFFF5F5F5L, "yellow" to 0xFFFFFF00L,
        "yellowgreen" to 0xFF9ACD32L, "transparent" to 0x00000000L
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
        // 函数式颜色(rgba?/hsla?)括号内可含空格,先压掉再按空白拆 token("rgb(0, 0, 0)" 不会被拆坏)
        val packed = Regex("(rgba?|hsla?)\\([^)]*\\)").replace(s) { it.value.replace(Regex("\\s+"), "") }
        for (tok in packed.split(Regex("\\s+"))) {
            val t = tok.lowercase()
            val bw = borderWidthEm(t)
            if (bw != null) { w = bw; continue }
            val bs = borderStyleVal(t)
            if (bs > 0) { style = bs; continue }
            val c = parseColor(t)
            if (c != null) { color = c; continue }
            // 认不出的 token 宽容跳过
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
        val indentCss: CssLen?,
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
    internal class Builder(val docDir: String, fontsIn: Map<String, String>? = null, val dataUriSink: DataUriSink? = null) {

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
        var paraIndentCss: CssLen? = null
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
        // br 触发的收口: 新段与上一段是 <br/> 相邻(同段内强制换行,排版层跳过段距)
        var brPending = false

        // 快照/恢复排版上下文(元素进出)
        fun snapshotLayout(): Array<Any?> = arrayOf(
            paraAlign, paraIndentCss, paraAboveEm, paraBelowEm,
            paraLeftEm, paraRightEm, paraWidthEm, paraWidthAlign, paraLineMult, paraFloatSide, paraBreakAll
        )

        fun restoreLayout(s: Array<Any?>) {
            paraAlign = s[0] as Int
            paraIndentCss = s[1] as CssLen?
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
            if (l.indentCss != null) paraIndentCss = l.indentCss
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
                    indentCss = paraIndentCss,
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
                    breakAll = paraBreakAll,
                    brBefore = brPending
                )
                brPending = false
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

        // WEBVIEW 块段: 投影放完整文本(非 U+FFFC,目录/TTS/搜索不跳过内容);
        // 位图自带盒样式/对齐,不设 boxStyle(防绘制层双重画盒)
        fun addWebViewBlock(text: String, html: String, shell: String, docDir: String) {
            out += Paragraph(
                text, emptyList(), ParaKind.WEBVIEW,
                anchor = openAnchors.firstOrNull()?.also { openAnchors.removeFirst() },
                align = paraAlign,
                spaceAboveEm = paraAboveEm,
                spaceBelowEm = paraBelowEm,
                marginLeftEm = effectiveLeft(),
                marginRightEm = effectiveRight(),
                blockHtml = html,
                ancestorShell = shell,
                blockDocDir = docDir
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

        fun pendingInline(start: Int, ref: String, sup: Boolean = false) {
            pendingInline += com.yukino.tool.module.reader.common.InlineImg(start, ref, sup)
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
                val ref = imageRefOf(img?.attr("src")?.trim() ?: "", b)
                if (ref != null) {
                    val (s, e) = b.appendTextTracked(HtmlTextExtractor.IMAGE_PLACEHOLDER)
                    if (e > s) {
                        b.pendingNote(s, e, frag)
                        b.pendingInline(s, ref, sup = true)   // noteref 图片角标 = 上标形态
                    }
                }
            }
            return
        }

        // CSS 规则(类/元素/复合选择器) + style 属性 → 合并属性表;run 级样式作用于本元素全部子孙
        val props = propsFor(node, cssRules)
        val saved = b.cur
        var deco = runDecoFromProps(props)
        // font-size: larger/smaller 相对父字号(CSS 规范比值 1.2);其余 font-size 形态
        // 走绝对倍率(既有简化,不做 em 链式复合)
        when (props["font-size"]?.trim()?.lowercase()) {
            "larger" -> deco = deco.copy(sizeEm = (saved.sizeEm ?: 1f) * 1.2f)
            "smaller" -> deco = deco.copy(sizeEm = (saved.sizeEm ?: 1f) / 1.2f)
        }
        b.cur = mergeRunCtx(saved, deco)
        if (id.isNotEmpty()) b.openAnchors.addLast(id)

        // 七期: 盒样式(底色/边框/圆角/阴影/背景图/内边距)——覆盖期间产出的段落归入该盒
        val box = parseBoxStyle(props, b.docDir)
        if (box != null) b.boxStack.addLast(box)

        // 段级排版属性(子未设用父,离开恢复)
        // 装饰盒的 padding 上下不再折入段距: 留白由物化层盒矩形上下外扩体现,
        // 两处都算会把盒内行距/段缝撑大一倍(t-box1 气泡场景);左右缩进保留
        // (折行宽需要它,矩形外扩不改变折行)
        val layoutProps = if (box != null && (box.padTopEm > 0f || box.padBottomEm > 0f)) {
            // padding 简写改写为"上下置 0,左右保留"(分边覆盖简写),再交给常规折算
            val q = expand4(props["padding"] ?: "0")
            val r = props["padding-right"] ?: q[1]
            val l = props["padding-left"] ?: q[3]
            val m = HashMap(props)
            m["padding"] = "0 $r 0 $l"
            m.remove("padding-top"); m.remove("padding-bottom")
            m.remove("padding-right"); m.remove("padding-left")
            m
        } else props
        val savedLayout = b.snapshotLayout()
        b.applyLayout(parseParaLayout(layoutProps))

        try {
            // 混合渲染: 块级元素命中自绘边界信号 → 整块降级 WebView 位图(子树不走常规提取)。
            // table 有专属判定(嵌套/超 500 格/表内信号),在 emitTable 的入口处理
            if (name != "table" && name in BLOCK && needsWebViewBlock(node, cssRules)) {
                b.flush()
                b.addWebViewBlock(
                    flattenBlockText(node).ifBlank { IMAGE_PLACEHOLDER },
                    node.outerHtml(), ancestorShellOf(node), b.docDir
                )
            } else when {
                name == "br" -> {
                    // br = 同段内强制换行: 前面的内容先收口,再标记"下一段与上一段 br 相邻"
                    // (排版层据此跳过段距)
                    b.flush()
                    b.brPending = true
                }
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

    // img src → 解压根相对 ref(相对路径 resolveHref;data URI 经 sink 落盘;外部 URL/拒收 null)
    private fun imageRefOf(src: String, b: Builder): String? = when {
        src.isEmpty() || src.startsWith("http", true) -> null
        src.startsWith("data:", true) -> {
            val (mime, payload) = parseDataUri(src) ?: return null
            b.dataUriSink?.accept(mime, payload)?.takeIf { it.isNotBlank() }
        }
        else -> resolveHref(b.docDir, percentDecode(src)).takeIf { it.isNotBlank() }
    }

    // img: 任何位置独立成图片段(外部 URL 忽略——外部资源不入正文;data URI 经 sink 落盘接入)。
    // 七期: style/class 的 width 定宽随段落(物化期按目标宽等比缩放)
    private fun emitImage(node: Element, b: Builder, cssRules: List<CssRule>) {
        val ref = imageRefOf(node.attr("src").trim(), b) ?: return
        val w = CssLen.parse(propsFor(node, cssRules)["width"] ?: "")
        b.addImage(ref, w)
    }

    // 表格(七期批次四): 真渲染数据提取。colspan/rowspan 网格展开(含被占位跳过);
    // 嵌套表与超阈值(>500 格)仍降级占位。单元格内容经子 Builder 提取投影与 runs
    // (段间单空格拼接,runs 平移),格级样式取 class/style(对齐/垂直对齐/底色/边框/th 加粗)
    private fun emitTable(node: Element, b: Builder, cssRules: List<CssRule>, noteIds: Set<String>) {
        // 混合渲染: 嵌套表/超 500 格/表内行内装饰(圆圈章号)等边界外信号 → 整表降级 WebView 位图
        if (needsWebViewTable(node, cssRules)) {
            b.flush()
            b.addWebViewBlock(
                flattenBlockText(node).ifBlank { IMAGE_PLACEHOLDER },
                node.outerHtml(), ancestorShellOf(node), b.docDir
            )
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
                val (text, runs, cellImg) = extractCellContent(cellEl, b, cssRules, noteIds)
                val props = propsFor(cellEl, cssRules)
                // td style/class 的 width 列宽提示(单列格,同列取首次)
                if ((cellEl.attr("colspan").toIntOrNull() ?: 1) <= 1) {
                    com.yukino.tool.module.reader.common.CssLen.parse(props["width"] ?: "")?.let {
                        colHints.putIfAbsent(c, it)
                    }
                }
                if (text.isNotBlank() || tag == "th" || cellImg != null) {
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
                        header = tag == "th",
                        imgRef = cellImg
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
    // 子上下文不带段落级排版(对齐由 TableCell.align 承载)。
    // 格内图片段(第一张)记 ref 随格返回(布局期按格宽等比撑行高,绘制期画位图);
    // 其图片不进格文本——U+FFFC 在格内无占位管线
    private fun extractCellContent(
        el: Element, b: Builder, cssRules: List<CssRule>, noteIds: Set<String>
    ): Triple<String, List<Run>, String?> {
        val sub = Builder(b.docDir, null, b.dataUriSink)
        // td/th 自身的 run 级样式(颜色/字号/粗斜)随格内文字落地——格内容提取 walk 的是
        // td 的孩子,td 分支不经 walkElement,装饰上下文需在此预置
        sub.cur = mergeRunCtx(sub.cur, runDecoFromProps(propsFor(el, cssRules)))
        for (c in el.childNodes()) walk(c, sub, ArrayDeque(), noteIds, cssRules)
        sub.flush()
        var text = ""
        var off = 0
        var imgRef: String? = null
        val runs = ArrayList<Run>()
        for (p in sub.result()) {
            if (p.isImage) {
                if (imgRef == null) imgRef = p.imageRef
                continue
            }
            if (text.isNotEmpty()) { text += " "; off += 1 }
            for (r in p.runs) runs += r.copy(start = r.start + off, end = r.end + off)
            text += p.text
            off += p.text.length
        }
        // run.fontId 已按子 Builder 自身表(可能为空)分配——单元格内不引用字体,清零防越界
        return Triple(text, runs.map { if (it.fontId != null) it.copy(fontId = null) else it }, imgRef)
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

    // font-size → 相对字号倍率(em 值/px÷16/百分比/CSS 关键词;larger/smaller 由调用侧相对父字号复合)
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
