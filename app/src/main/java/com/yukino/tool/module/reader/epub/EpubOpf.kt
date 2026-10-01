package com.yukino.tool.module.reader.epub

import android.util.Xml
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser

// EPUB 包级解析(纯数据产物,字符串输入,可本地单测):
//   container.xml → OPF 路径;OPF → 元数据/manifest/spine;NCX / Nav Doc → 目录

const val MEDIA_TYPE_NCX = "application/x-dtbncx+xml"
const val MEDIA_TYPE_XHTML = "application/xhtml+xml"

// manifest 条目
class ManifestItem(
    val id: String,
    val href: String,        // 相对 OPF 的原始 href(未解码)
    val mediaType: String,
    val properties: String   // EPUB3 属性串(空格分隔,如 "nav cover-image")
) {
    fun hasProperty(p: String): Boolean = properties.split(' ').any { it == p }
}

// spine 条目
class SpineItemref(val idref: String, val linear: Boolean)

// OPF 包: 元数据 + manifest + spine
class EpubPackage(
    val title: String?,
    val author: String?,
    val opfDir: String,                      // OPF 所在目录(zip 内路径,"" = 根)
    val items: Map<String, ManifestItem>,    // id → 条目
    val spine: List<SpineItemref>,
    val ncxId: String?                       // spine@toc 指向的 NCX id
)

// 目录条目: 文档路径(已解码,相对 zip 根,不含片段)+ 章节内锚点 + 标题
class TocEntry(val path: String, val fragment: String?, val title: String)

// ---------- 路径工具 ----------

// href 剥离 #fragment
internal fun stripFragment(href: String): Pair<String, String?> {
    val i = href.indexOf('#')
    return if (i < 0) href to null else href.substring(0, i) to href.substring(i + 1).ifEmpty { null }
}

// 百分解码(手工 %XX,不用 URLDecoder——它会把 '+' 变空格,破坏含 + 的文件名)
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val hex = runCatching { s.substring(i + 1, i + 3).toInt(16) }.getOrNull()
            if (hex != null) {
                out.append(hex.toChar())
                i += 3
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

// 相对路径解析为规范化绝对路径(处理 "./" 与 "../")
internal fun resolveHref(baseDir: String, href: String): String {
    val parts = ArrayDeque<String>()
    if (baseDir.isNotBlank()) parts.addAll(baseDir.split('/').filter { it.isNotBlank() })
    for (seg in href.split('/')) {
        when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(seg)
        }
    }
    return parts.joinToString("/")
}

// ---------- 通用 XML 遍历 ----------
// 限定名遍历(name 可能带前缀,如 "opf:package"/"dc:title");attrs 键为无前缀属性名。
// 一律按本地名(去前缀)匹配,真实世界的 EPUB 常见前缀化 OPF
private fun localName(name: String): String = name.substringAfterLast(':')

private class XmlWalk(xml: String) {
    val parser: XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(xml.reader())
    }

    var text = StringBuilder()
    var event: Int = parser.eventType

    fun next(): Int {
        event = parser.next()
        return event
    }

    val name: String get() = localName(parser.name)
    val depth: Int get() = parser.depth

    fun attr(key: String): String? = parser.getAttributeValue(null, key)

    fun forEachTag(onTag: (name: String, depth: Int) -> Unit) {
        var e = event
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> onTag(name, depth)
                XmlPullParser.TEXT -> text.append(parser.text)
            }
            e = next()
        }
    }
}

// ---------- container.xml ----------

// 解析 container.xml 内容 → rootfile 全路径;解析不出抛 EpubFormatException
internal fun parseContainerXml(xml: String): String {
    val w = XmlWalk(xml)
    var rootPath: String? = null
    w.forEachTag { name, _ ->
        if (name == "rootfile" && rootPath == null) {
            rootPath = w.attr("full-path")?.takeIf { it.isNotBlank() }
        }
    }
    return rootPath ?: throw EpubFormatException("container.xml 缺少 rootfile,不是有效的 EPUB")
}

// ---------- OPF ----------
// 元数据文本(title/creator)在标签闭 合时取累积文本;manifest/spine 按祖先标签判定
internal fun parseOpf(xml: String, opfDir: String): EpubPackage {
    val w = XmlWalk(xml)
    var title: String? = null
    var author: String? = null
    val items = LinkedHashMap<String, ManifestItem>()
    val spine = ArrayList<SpineItemref>()
    var ncxId: String? = null
    val stack = ArrayDeque<String>()
    var capture: String? = null   // 正在收集文本的元数据字段: "title"/"creator"

    var e = w.event
    while (e != XmlPullParser.END_DOCUMENT) {
        when (e) {
            XmlPullParser.START_TAG -> {
                val n = w.name
                stack.addLast(n)
                when {
                    n == "title" && capture == null -> { capture = "title"; w.text.setLength(0) }
                    n == "creator" && capture == null -> { capture = "creator"; w.text.setLength(0) }
                    n == "item" && stack.contains("manifest") -> {
                        val id = w.attr("id")
                        val href = w.attr("href")
                        if (id != null && href != null) {
                            items[id] = ManifestItem(id, href, w.attr("media-type") ?: "", w.attr("properties") ?: "")
                        }
                    }
                    n == "itemref" && stack.contains("spine") -> {
                        val idref = w.attr("idref")
                        if (idref != null) spine += SpineItemref(idref, w.attr("linear") != "no")
                    }
                    n == "spine" -> ncxId = w.attr("toc")
                }
            }
            XmlPullParser.TEXT -> if (capture != null) w.text.append(w.parser.text)
            XmlPullParser.END_TAG -> {
                val n = w.name
                if (capture == "title" && n == "title") {
                    val t = w.text.toString().trim()
                    if (t.isNotEmpty() && title == null) title = t
                    capture = null
                } else if (capture == "creator" && n == "creator") {
                    val a = w.text.toString().trim()
                    if (a.isNotEmpty() && author == null) author = a
                    capture = null
                }
                if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        e = w.next()
    }
    return EpubPackage(title, author, opfDir, items, spine, ncxId)
}

// ---------- NCX(EPUB2 目录 toc.ncx) ----------

internal fun parseNcx(xml: String, ncxDir: String): List<TocEntry> {
    val w = XmlWalk(xml)
    val out = ArrayList<TocEntry>()
    val stack = ArrayDeque<String>()
    var pendingTitle: String? = null
    var pendingSrc: String? = null
    var inNavLabel = false

    var e = w.event
    while (e != XmlPullParser.END_DOCUMENT) {
        when (e) {
            XmlPullParser.START_TAG -> {
                val n = w.name
                stack.addLast(n)
                when (n) {
                    "navLabel" -> { inNavLabel = true; w.text.setLength(0) }
                    "content" -> pendingSrc = w.attr("src")
                }
            }
            XmlPullParser.END_TAG -> {
                val n = w.name
                when (n) {
                    "navLabel" -> {
                        inNavLabel = false
                        val t = w.text.toString().trim()
                        if (t.isNotEmpty()) pendingTitle = t
                    }
                    "content" -> {}   // src 已在 START_TAG 取到
                    "navPoint" -> {
                        val src = pendingSrc
                        // 空标题的条目丢弃(不少粗制 EPUB 的 NCX 全空)——
                        // 交给导入侧用文档内标题/文件名兜底,避免章名退化成路径
                        if (src != null && pendingTitle != null) {
                            val (path, frag) = stripFragment(src)
                            if (path.isNotBlank()) {
                                out += TocEntry(resolveHref(ncxDir, percentDecode(path)), frag, pendingTitle)
                            }
                        }
                        pendingTitle = null
                        pendingSrc = null
                    }
                }
                if (stack.isNotEmpty()) stack.removeLast()
            }
            XmlPullParser.TEXT -> if (inNavLabel) w.text.append(w.parser.text)
        }
        e = w.next()
    }
    return out
}

// ---------- Nav Doc(EPUB3 目录) ----------

// 取 nav[epub:type=toc] 内的链接;无则退化为文档内全部 a[href](取前 500 条防病态文档)
internal fun parseNav(html: String, navDir: String): List<TocEntry> {
    val doc = Jsoup.parse(html)
    val nav = doc.allElements.firstOrNull {
        it.tagName() == "nav" && it.attr("epub:type").split(' ').contains("toc")
    }
    val links = (nav?.select("a[href]") ?: doc.select("a[href]")).take(500)
    return links.mapNotNull { a ->
        val href = a.attr("href")
        if (href.isBlank() || href.startsWith("http")) return@mapNotNull null
        val (path, frag) = stripFragment(href)
        if (path.isBlank()) return@mapNotNull null
        TocEntry(resolveHref(navDir, percentDecode(path)), frag, a.text().trim().ifEmpty { path })
    }
}
