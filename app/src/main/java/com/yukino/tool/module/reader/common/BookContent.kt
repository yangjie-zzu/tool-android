package com.yukino.tool.module.reader.common
// ==================== 内容模型与内容源抽象 ====================
// 导入侧(TxtImporter / EpubImporter 各自主流程)的产物收敛到这里:
// 排版层(BookPager)与渲染层(ReaderPageView)只认识 BookContent/ChapterDocument,
// 不认识具体格式——两种格式的主流程在公共层相遇,互不感知。

// 内联样式位标记(Run.style)。富文本只影响绘制,不参与偏移/进度/选择等一切投影机制
object RunStyle {
    const val BOLD = 1
    const val ITALIC = 2
    const val UNDERLINE = 4
    const val STRIKE = 8
    const val SUP = 16      // 上标: 字号缩小 + 基线上移
    const val SUB = 32      // 下标: 字号缩小 + 基线下移
}

// 段内 [start, end) 区间(纯文本投影坐标)的样式;style 为 RunStyle 位或。
// 段内排序不重叠、相邻同样式已合并(解析侧保证)。
// 七期批次三: sizeEm 相对字号倍率(font-size,CSS 关键词/px 已折 em 倍率),
// color 书内前景色(ARGB,夜间主题绘制层做亮度适配),shadow text-shadow(固定近似参数),
// fontId 章级字体表下标(@font-face 的 font-family)——四者 null/0 = 跟随全局
data class Run(
    val start: Int,
    val end: Int,
    val style: Int,
    val sizeEm: Float? = null,
    val color: Long? = null,
    val shadow: Boolean = false,
    val fontId: Int? = null
)

enum class ParaKind { TEXT, IMAGE, TABLE, WEBVIEW }

// 段内脚注角标区间(纯文本投影坐标): 角标文本保留在投影中(如 "[1]"),
// noteId 指向章级 footnotes 表(不进正文阅读流)
class NoteAnchor(val start: Int, val end: Int, val noteId: String)

// 段内行内图片(五期): start 为投影中 U+FFFC 占位字符的段内偏移,
// ref 为图片路径(章文件存相对路径,加载侧转绝对)。行内渲染,不独占行。
// sup = noteref 图片角标(书源 <sup> 内): 绘制按上标基线提升
class InlineImg(val start: Int, val ref: String, val sup: Boolean = false)

// CSS 长度(七期): em 绝对值或版心百分比(CSS 的 margin/width 百分比相对包含块宽度)。
// 排版期换算 px: em × 字号,百分比 × 可用宽
@kotlinx.serialization.Serializable
data class CssLen(val v: Float, val pct: Boolean = false) {
    fun px(fontPx: Float, availWidthPx: Float): Float =
        if (pct) v / 100f * availWidthPx else v * fontPx

    companion object {
        // "1.5em" / "24px"(÷16 折 em) / "10%" / "0" → CssLen;其他单位/形态 null
        fun parse(raw: String): CssLen? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            if (s == "0") return CssLen(0f)
            Regex("^(-?[\\d.]+)em$").find(s)?.let { return it.groupValues[1].toFloatOrNull()?.let { v -> CssLen(v) } }
            Regex("^(-?[\\d.]+)px$").find(s)?.let { return it.groupValues[1].toFloatOrNull()?.let { v -> CssLen(v / 16f) } }
            Regex("^(-?[\\d.]+)%$").find(s)?.let { return it.groupValues[1].toFloatOrNull()?.let { v -> CssLen(v, pct = true) } }
            return null
        }
    }
}

// 边框样式(七期): 每边(宽, 样式, 颜色)。style:
// 1 solid / 2 dotted / 3 dashed / 4 double / 5 ridge / 6 groove / 7 inset / 8 outset
// (5..8 绘制层两色立体模拟;0/widthEm<=0 = 无边)
@kotlinx.serialization.Serializable
data class EdgeStyle(
    val widthEm: Float = 0f,
    val style: Int = 0,
    val color: Long = 0xFF000000
)

// 盒样式(七期批次二): 底色/背景图/圆角/阴影/内边距/四边边框。
// 带盒样式的块元素覆盖的连续段落为一个"盒组",绘制时按组聚合矩形(底色+边框),
// 文本左右缩进 = margin/padding 折算,padTop/padBottom 只外扩绘制矩形不移动文本
@kotlinx.serialization.Serializable
data class BoxStyle(
    val bg: Long? = null,
    val bgImage: String? = null,      // 相对解压根路径(加载侧转绝对);cover 铺满盒矩形
    val radius: CssLen? = null,       // 圆角(px/em 折 em,% 排版期相对版心宽换算)
    val shadow: Boolean = false,
    val heightCss: CssLen? = null,    // 批次四c: 固定高(盒矩形高 = max(内容高,固定高),内容垂直居中)
    val rotateDeg: Float? = null,     // 批次四d: transform rotate(度,绘制层盒中心旋转)
    val widthCss: CssLen? = null,     // 批次四修复: 盒自身内容宽(盒矩形由此定位,区别于段落继承折行宽)
    val marginAuto: Int = 0,          // 盒自身 margin auto 定位: 1 居中(双 auto) 2 贴右(左 auto) 3 贴左(右 auto)
    val marginLeft: CssLen? = null,   // 盒自身 margin-left(非 auto 值)
    val marginRight: CssLen? = null,  // 盒自身 margin-right(非 auto 值)
    val padTopEm: Float = 0f,
    val padBottomEm: Float = 0f,
    val padLeftEm: Float = 0f,
    val padRightEm: Float = 0f,
    val edges: List<EdgeStyle> = emptyList()   // 固定 4 项: 上右下左
) {
    companion object {
        val NONE = BoxStyle()
    }
}

// 表格单元格(七期批次四): 网格展开后的占位(跨行跨列以 row/col 起点 + span 表达)。
// 内容 = 单元格投影文本 + 富文本 runs(段间以单空格拼接);垂直对齐 0=top 1=middle 2=bottom
class TableCell(
    val row: Int,
    val col: Int,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val text: String,
    val runs: List<Run> = emptyList(),
    val align: Int = 0,               // 水平对齐(0 默认/1 中/2 右/3 左/4 两端)
    val vAlign: Int = 1,              // 垂直对齐(top/middle/bottom)
    val bg: Long? = null,             // 单元格底色
    val edges: List<EdgeStyle> = emptyList(),   // 四边框(上右下左)
    val header: Boolean = false,      // th 表头(默认加粗居中)
    val imgRef: String? = null        // 格内图片(取第一张;相对解压根路径,加载侧转绝对)
)

// 表格(七期批次四): 真渲染数据。行高列宽排版期按内容分配;跨页按行切分
class TableData(
    val rows: Int,
    val cols: Int,
    val cells: List<TableCell>,
    val collapse: Boolean = true,     // border-collapse: true 合并边框;false 分离(border-spacing 生效)
    val spacingEm: Float = 0f,        // border-spacing(em,collapse=false 时)
    val colWidths: List<CssLen?> = emptyList()  // 批次四修复: td width 提示(列 → 宽,空 = 未提示)
)

// 段落级书内排版(四期 CSS 子集;七期扩展):
// align 0=默认(跟随全局) 1=居中 2=右对齐 3=左对齐(显式) 4=两端对齐(显式);
// indentEm 非空覆盖全局首行缩进(null=跟随全局);
// spaceAboveEm/spaceBelowEm 书内块级 margin(七期起支持 em/px/%),排版时按"段距跟随书内"开关取舍;
// heading = 1..6 表示该段源自 hn 标题(h2 拆章依据),0 = 普通段落;
// 七期: marginLeft/RightEm 整段左右缩进(margin/padding 折算),widthEm 定宽
// (排版期推出右侧留白,左右 auto 时居中),lineSpacingMult 段级行距倍率(覆盖全局行距),
// boxStyle 非空 = 段落在该盒内(相同样式相邻段落聚合绘制底色/边框)
class Paragraph(
    val text: String,
    val runs: List<Run> = emptyList(),
    val kind: ParaKind = ParaKind.TEXT,
    val imageRef: String? = null,
    val anchor: String? = null,
    val notes: List<NoteAnchor> = emptyList(),
    val align: Int = 0,
    val indentEm: Float? = null,
    val spaceAboveEm: CssLen? = null,
    val spaceBelowEm: CssLen? = null,
    val heading: Int = 0,
    val inlineImages: List<InlineImg> = emptyList(),
    val marginLeftEm: CssLen? = null,
    val marginRightEm: CssLen? = null,
    val widthEm: CssLen? = null,
    val widthAlign: Int = 0,            // 定宽对齐(margin auto 语义): 1=居中(双 auto) 2=贴右(左 auto) 3=贴左(右 auto)
    val lineSpacingMult: Float? = null,
    val boxStyle: BoxStyle? = null,
    val table: TableData? = null,     // 七期批次四: 表格段(投影 U+FFFC 占位,真渲染)
    val floatSide: Int = 0,           // 批次四b: 浮动盒(1=right 2=left;带 width+height 才真环绕,否则六期右对齐降级)
    val breakAll: Boolean = false,    // 批次四e: word-break:break-all(词中可断,手动逐字折行)
    val brBefore: Boolean = false,    // 七期补: 与上一段是 <br/> 相邻(同段内强制换行,排版层段距归零)
    val indentCss: CssLen? = null,    // text-indent 全单位形态(em/px/%;优先于老字段 indentEm)
    val blockHtml: String? = null,    // 混合渲染: WEBVIEW 段的块 HTML 片段(jsoup 序列化,原样)
    val ancestorShell: String? = null, // 祖先壳: body 到块元素的逐层开标签(重建 CSS 上下文)
    val blockDocDir: String = ""      // 块来源文档相对解压根的目录(片段内相对引用的解析基准)
) {
    val isImage: Boolean get() = kind == ParaKind.IMAGE
    val isTable: Boolean get() = table != null
    val isFloat: Boolean get() = floatSide != 0
}

// 章文档: 标题 + 正文段落序列。bodyText 是段落的纯文本投影(段间一个 \n),
// 分页/进度/选区锚定的全书偏移全部定义在投影上;
// footnotes = 本章脚注内容表(noteId → 纯文本,弹层展示),不参与排版与偏移
class ChapterDocument(
    val title: String,
    val paragraphs: List<Paragraph>,
    val footnotes: Map<String, String> = emptyMap(),
    val fontIds: Map<Int, String> = emptyMap(),       // 七期: run.fontId → family
    val fontFiles: Map<String, String> = emptyMap(),  // family → 字体文件绝对路径
    val cssHrefs: List<String> = emptyList(),  // 混合渲染: 原文档 head 外部样式 href 原样列表
    val cssInline: List<String> = emptyList(), // 混合渲染: 原文档 <style> 块原文列表
    val bodyDecor: Boolean = false,            // 页面级背景信号: body 带背景图/色
    val bodyHtml: String = "",                 // 页面级背景章聚合: 整章 body innerHTML
    val bodyShell: String = ""                 // 页面级背景章聚合: body 开标签壳
) {
    val bodyText: String get() = paragraphs.joinToString("\n") { it.text }
}

// 内容源: 排版层眼中的"一本书"。章节区间约定:
//   章正文在全书偏移轴上占 [chapterStart, chapterStart + bodyText.length),
//   章与章之间隔一个虚拟换行偏移(txt 例外——源文本天然连续,区间无缝拼接);
//   textAt 按 chapterDoc 的纯文本投影裁切,供复制/分享等取选中文本
interface BookContent {
    val bookTitle: String
    val totalChars: Long
    val chapterCount: Int                      // 正文章数(≥1;无章节书 = 整本单章)

    fun chapterTitle(index: Int): String
    fun chapterStart(index: Int): Long         // 章首全书偏移(含尚未剥掉的正文自带标题)
    fun chapterDoc(index: Int): ChapterDocument

    // 全书偏移区间 [start, end) 的选中文本(章界处补换行)
    fun textAt(startGlobal: Long, endGlobal: Long): String

    // ---- 图片支持(二期;纯文本内容源用默认空实现) ----

    // 图片像素尺寸(imageRef 为图片文件绝对路径;不可解码返回 null)。
    // 排版侧按版心宽等比换算占位高度
    fun imageBounds(imageRef: String): android.graphics.Rect? = null

    // 封面文件绝对路径(null/文件不存在 = 无封面)。阅读页封面页与书架缩略图共用
    val coverPath: String? get() = null

    // ---- 锚点与脚注(三期;TXT 用默认空实现) ----

    // 章内锚点(anchorId,目录 fragment)的章内投影偏移;找不到返回 null(调用方落章首)
    fun anchorOffset(chapterIndex: Int, anchorId: String): Long? = null

    // 全书偏移处的脚注角标 → (noteId, 脚注内容);非角标位置返回 null。
    // 供点击命中: tap → 字符全书偏移 → 本查询
    fun footnoteAt(globalOffset: Long): Pair<String, String>? = null

    // ---- 装饰章(七期): 章首段带装饰盒的 CSS 排版页,整章一页按快照位图呈现 ----

    // 该章是否装饰章(分页语义: 整章一页不跨页;TXT 恒否)
    fun isDecorative(chapterIndex: Int): Boolean = false

    // 装饰章快照位图文件绝对路径;null = 未生成(页面临时以近似排版占位,生成完成后重物化)
    fun decoSnapshot(chapterIndex: Int): String? = null

    // ---- 混合渲染(WEBVIEW 块级降级): 块位图缓存根目录(解压根) ----
    // TXT/无实现返回 null → 块渲染整体不生效,WEBVIEW 段按普通段落自绘兜底
    fun webBlockRoot(): java.io.File? = null
}

// TXT 内容源: 全书单文本流 + 章节偏移表切片。无章节书归一化为"整本单章"
class TxtBookContent(private val book: ReaderBook, private val fullText: String) : BookContent {

    private val chapters = book.chapters.ifEmpty { listOf(ChapterIndex(book.title, 0L)) }

    override val bookTitle: String get() = book.title
    override val totalChars: Long get() = book.totalChars
    override val chapterCount: Int get() = chapters.size

    override fun chapterTitle(index: Int): String = chapters[index].title

    override fun chapterStart(index: Int): Long = chapters[index].startChar

    override fun chapterDoc(index: Int): ChapterDocument {
        val title = chapters[index].title
        val start = chapters[index].startChar.toInt().coerceIn(0, fullText.length)
        val end = if (index + 1 < chapters.size) {
            chapters[index + 1].startChar.toInt().coerceIn(start, fullText.length)
        } else fullText.length
        return ChapterDocument(title, fullText.substring(start, end).split('\n').map { Paragraph(it) })
    }

    // 全书文本裁切,章界处补换行(章首剥掉的原标题行不落在任何正文页上,
    // 选区永不覆盖它,子串天然干净;相邻章内容在源文本中是连续的,补 \n 还原段落分隔)
    override fun textAt(startGlobal: Long, endGlobal: Long): String {
        val s = startGlobal.toInt().coerceIn(0, fullText.length)
        val e = endGlobal.toInt().coerceIn(s, fullText.length)
        if (e <= s) return ""
        val sb = StringBuilder(e - s + 8)
        var prev = s
        for (ch in book.chapters) {
            val c = ch.startChar.toInt()
            if (c <= prev) continue
            if (c >= e) break
            sb.append(fullText, prev, c).append('\n')
            prev = c
        }
        sb.append(fullText, prev, e)
        return sb.toString()
    }
}

// 导入结果: 两条导入主流程共用(原 TxtImporter.ImportResult 上提)
sealed interface ImportResult {
    data class Success(val book: ReaderBook) : ImportResult
    data class FolderImported(val groupCount: Int, val bookCount: Int) : ImportResult
    data class Failed(val reason: String) : ImportResult
}
