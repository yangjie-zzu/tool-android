package com.yukino.tool.module.reader.common
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// 页类型: 封面/正文/封底。章节名不是页类型——它作为样式块进入章节文本流(ChapterComposer)
@kotlinx.serialization.Serializable
enum class PageKind { COVER, CONTENT, BACK }

// 页描述(轻量,全书页目录的元素;纯数据可全量常驻)。
// 可序列化: 整本分页结果持久化缓存(ReaderStore.loadSpecs),二次进入免整本重排
@kotlinx.serialization.Serializable
class PageSpec(
    val kind: PageKind,
    val chapterIndex: Int,        // -1=封面, 0..N-1=正文, N=封底
    val chapterPageIndex: Int,    // 章内页下标(0 基,页脚 x/y 用)
    val chapterPageCount: Int,    // 章内总页数
    val globalCharOffset: Long,   // 页首字符的全书偏移(锚点/百分比/恢复,与 Progress 同名对齐)
    val chapterTitle: String      // 页眉文案;封面/封底空串
)

// 行类别: 标题行/正文行/空行。由行在合成文本中的位置与内容判定
enum class LineKind { TITLE, BODY, BLANK }

// 一行: 合成文本的字符区间 + 网格化行高。整章一次断行的产物,不可变,页只是行窗口
class TextLine(
    val start: Int,           // 合成文本区间 [start, end),不含行尾换行
    val end: Int,
    val kind: LineKind,
    val isParaStart: Boolean, // 段落首行(段前距挂其上方)
    val pitch: Int,           // 行总高 = 段前距 + 行距,均为网格整数倍
    val paraAbove: Int,       // pitch 中的段前距部分(网格整数倍)
    val ascentAbs: Int        // 基线 = 行顶 + 段前距 + ascentAbs(字体度量 + 行距空白对分偏移)
)

// 全章行模型: 物化与分页共用,断行只发生一次。composed 上的样式 span 仅供断行度量,
// 绘制由行类别与段落 runs(经 paraRanges 折算)决定
class ChapterLines(
    val composed: CharSequence,   // 章节名 + "\n\n" + 正文
    val bodyStart: Int,           // 正文区起点(之前是标题块)
    val bodyZero: Long,           // 正文零点的全书偏移(章起点 + 剥掉的标题字符数)
    val lines: List<TextLine>,
    val paras: List<Paragraph> = emptyList(),     // 剥标题后的段落(与 paraRanges 对齐)
    val paraRanges: List<IntRange> = emptyList(), // 各段在 composed 中的区间(正文区,不含换行)
    val imageDrawSizes: Map<Int, ImageSize> = emptyMap(), // 图片段显示尺寸(px,paraIndex → 尺寸)
    val inlineSizes: Map<String, ImageSize> = emptyMap(), // 五期: 行内图片显示尺寸(ref → 尺寸)
    val overrideLines: Map<Int, List<IntRange>> = emptyMap(), // 七期: 右缩进/定宽段的独立断行(paraIndex → composed 行区间)
    val fontIds: Map<Int, String> = emptyMap(),   // 七期批次三: run.fontId → family
    val fontFiles: Map<String, String> = emptyMap(), // family → 字体文件绝对路径
    val tableLayouts: Map<Int, TableLayout> = emptyMap(),  // 批次四: 表格段布局(paraIndex → 布局)
    val tableRowOfLine: Map<Int, Int> = emptyMap(),        // 批次四: lines 下标 → 表格行号(表格段)
    val avoidX: Map<Int, Float> = emptyMap()               // 批次四b: float 环绕避让偏移(paraIndex → 行 x 偏移)
)

// 图片行的显示尺寸(版心坐标系;按版心宽等比缩放,超高图缩到一页内)
class ImageSize(val width: Int, val height: Int)

// 行内样式段(相对行文本坐标)。绘制层按段切 paint;与两端对齐拉伸分段正交组合。
// 七期批次三: sizeEm 字号倍率/color 书内前景色/shadow text-shadow/font 字体 family
class LineStyle(
    val start: Int,
    val end: Int,
    val style: Int,
    val sizeEm: Float? = null,
    val color: Long? = null,
    val shadow: Boolean = false,
    val font: String? = null
)

// 可绘制的行(物化产物,坐标相对版心左上角)。基线布局在物化时算好,绘制层只做平移与 drawText
class DrawLine(
    val text: String,
    var x: Float,                 // 行首 x: 段首行为首行缩进,其余 0(自带缩进的段落缩进在字符里);
                                  // 物化期盒基准修正可改写(定宽盒内行的对齐基准随盒走)
    val baseline: Float,          // 图片行复用为"行顶 y"(图片从行顶绘制)
    val title: Boolean,           // true 用标题 paint(大字号加粗)
    val lineStartGlobal: Long,    // 行首字符的全书偏移(行内第 i 字符 = lineStartGlobal + i,正文区线性)
    val segments: List<LineSeg>? = null,   // 两端对齐拉伸分段(相对行首 x);null = 自然宽整行画
    val styles: List<LineStyle>? = null,   // 行内样式段(相对行文本坐标);null = 单一样式
    val imageRef: String? = null,          // 图片行: 图片文件绝对路径(非空 = 图片行,text 为空)
    val imageWidth: Float = 0f,            // 图片行显示尺寸(物化时按版心宽换算好)
    val imageHeight: Float = 0f,
    val inlineImages: List<DrawInline> = emptyList()   // 五期: 行内图片(字符下标+路径+显示尺寸)
)

// 行内图片标记(五期): charIdx 为行内字符下标(该字符 = 投影 U+FFFC 占位),
// ref 为图片绝对路径,w/h 为物化好的显示尺寸
class DrawInline(val charIdx: Int, val ref: String, val width: Int, val height: Int)

// 盒组绘制矩形(七期): 版心坐标,底色/背景图/边框/圆角/阴影由 style 描述。
// topOpen/bottomOpen = 盒组延续到相邻页(该缘不画横向边框,左右边照画)
class DrawBox(
    val style: BoxStyle,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val topOpen: Boolean,
    val bottomOpen: Boolean
)

// ---------- 表格(七期批次四) ----------

// 格内折行的一行: 行文本 + 行首字符在格文本中的偏移 + 富文本样式段(相对行文本)
class CellTextLine(val text: String, val startOff: Int, val styles: List<LineStyle>)

// 格矩形(表内坐标)与折行内容
class TableCellBox(
    val cell: TableCell,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val lines: List<CellTextLine>
)

// 表格布局(断行期一次算好,物化按页窗口切片): 列宽/行高/格几何
class TableLayout(
    val widths: List<Float>,
    val heights: List<Float>,
    val rowY: List<Float>,
    val cells: List<TableCellBox>,
    val totalWidth: Float,
    val totalHeight: Float,
    val padH: Float,
    val padV: Float,
    val gap: Float,
    val lineH: Float
)

// 表格片段(物化产物): 全表布局 + 本页窗口的行范围;表格整体顶可为负(从上页延续),
// 绘制层靠版心裁剪兜底
class DrawTable(
    val layout: TableLayout,
    val x: Float,
    val y: Float,
    val firstRow: Int,
    val lastRowExclusive: Int
)

class LineSeg(val text: String, val x: Float)

// 可渲染页: 自包含(行布局+页眉页脚文案),渲染层不接触章/行模型。
// 不可变——拖拽预览与落账引用同一实例,"看到的页"=="翻到的页"
class BookPage(
    val spec: PageSpec,
    val headerTitle: String,      // 空串不画
    val footerLabel: String,      // 空串不画
    val lines: List<DrawLine> = emptyList(),   // 正文页的行(相对版心顶的基线坐标)
    val boxes: List<DrawBox> = emptyList(),    // 七期: 盒组矩形(画在文字下层)
    val tables: List<DrawTable> = emptyList(), // 批次四: 表格片段(画在文字下层)
    val fontFiles: Map<String, String> = emptyMap(),  // 七期批次三: family → 字体文件路径(绘制层加载 Typeface)
    val virtualLayout: StaticLayout? = null,   // 封面/封底: 居中布局,与 lines 二选一
    val coverImage: String? = null,            // 封面页: 封面图路径(与 virtualLayout 二选一,优先图)
    val coverWidth: Int = 0,                   // 封面显示尺寸(物化时按版心宽等比换算;0 = 未就绪)
    val coverHeight: Int = 0,
    val decoImage: String? = null,             // 装饰章: 整页快照位图路径(优先于 lines 绘制)
    val decoWidth: Int = 0,                    // 装饰快照显示尺寸(物化时按版心宽等比换算;0 = 未就绪)
    val decoHeight: Int = 0
)

// 行网格高度计算(纯函数,本地单测覆盖): 行距增量加在行下方,段前距加在行上方
object LineGrid {

    fun paraAbove(paraExtraPx: Float, gridPx: Int): Int = PaginationEngine.gridCeil(paraExtraPx, gridPx)

    fun linePitch(naturalHeight: Float, lineExtraPx: Float, gridPx: Int): Int =
        PaginationEngine.gridCeil(naturalHeight + lineExtraPx, gridPx)

    fun blankPitch(gridPx: Int): Int = gridPx

    // 行距空白上下对分: 行框内"行高-自然高"的空白默认全在文字下方(Android 行距惯例),
    // 返回把基线下移的偏移量(空白/2),使页面上下留白对称。
    // 所有行统一偏移: 行高、行间距总量、分页、跨页行位对齐都不变
    fun centerShift(pitch: Int, naturalHeight: Float): Int = ((pitch - naturalHeight) / 2f).roundToInt()
}

// 章节文本合成: 章节名(1.4倍加粗)+空行+正文。
// 样式 span(标题字号/加粗/首行缩进/正文 Run)只供断行度量,
// 绘制由行类别与段落 runs(经 paraRanges 折算)决定
object ChapterComposer {
    const val TITLE_SCALE = 1.4f
    const val SUP_SUB_SCALE = 0.65f   // 上下标字号比例

    // 正文在合成文本中的起点: title + "\n\n"
    fun bodyStart(titleLength: Int): Int = titleLength + 2

    // 二期: 接收段落序列(带 Run),Run 转字符样式 span 参与断行度量。
    // 四期: 段级对齐(AlignmentSpan,center/right)与段级首行缩进(书内 indentEm 覆盖全局)。
    // 五期: 行内图片占位符按图片等比尺寸度量(ReplacementSpan 只管尺寸,绘制走自绘管线)。
    // 投影文本与一期 compose(title, body) 逐字符一致(段落 = joinToString("\n") 的切分)
    fun compose(
        title: String,
        paras: List<Paragraph>,
        typo: ResolvedTypography,
        imageBounds: ((String) -> Rect?)? = null
    ): Spanned {
        val sb = SpannableStringBuilder(title).append("\n\n")
        var pos = title.length + 2
        paras.forEachIndexed { i, p ->
            if (i > 0) { sb.append('\n'); pos++ }
            val paraStart = pos
            sb.append(p.text)
            for (run in p.runs) {
                if (run.end > run.start) applyRunSpan(sb, pos + run.start, pos + run.end, run.style, run.sizeEm)
            }
            // 五期: 行内图片占位符度量 span
            if (p.inlineImages.isNotEmpty() && imageBounds != null) {
                for (im in p.inlineImages) {
                    val idx = paraStart + im.start
                    if (idx >= pos) continue
                    val size = inlineImageDisplaySize(imageBounds(im.ref), typo)
                    sb.setSpan(
                        InlineImageSpan(size.width), idx, idx + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            pos += p.text.length
            // 段级对齐: 度量与断行由 StaticLayout 按 span 处理。
            // 1=center 2=right 用 AlignmentSpan;3=left(显式) 4=justify(显式) 无 span——
            // 左/两端由行绘制层控制(4 无视全局两端开关)。
            // 七期: 整段左缩进(ml)进 LeadingMarginSpan 每行生效,首行缩进(major 位)叠加。
            // ALIGN_OPPOSITE 在 LTR 文档下即右对齐
            val mlPx = (p.marginLeftEm?.px(typo.fontPx, typo.textWidth.toFloat()) ?: 0f)
                .coerceIn(0f, typo.textWidth * 0.45f)
            val alignment = when (p.align) {
                1 -> Layout.Alignment.ALIGN_CENTER
                2 -> Layout.Alignment.ALIGN_OPPOSITE
                else -> null
            }
            if (alignment != null) {
                sb.setSpan(AlignmentSpan.Standard(alignment), paraStart, pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (mlPx > 0f) {
                    // 居中/右对齐段: margin-left 收窄可用宽(无首行缩进)
                    sb.setSpan(LeadingMarginSpan.Standard(0, mlPx.toInt()), paraStart, pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                val indentPx = paraIndentPx(p, typo)
                if ((indentPx > 0f || mlPx > 0f) && p.text.isNotEmpty() && !leadingIndented(sb, paraStart)) {
                    // LeadingMarginSpan.Standard(major, minor): 首行 = major+minor, 其余 = minor
                    sb.setSpan(LeadingMarginSpan.Standard(indentPx.toInt(), mlPx.toInt()), paraStart, pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        sb.setSpan(RelativeSizeSpan(TITLE_SCALE), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    // 段首缩进: 书内 indentEm 覆盖全局设置(null = 全局;em 非负,0 = 显式不缩进)
    internal fun paraIndentPx(p: Paragraph?, typo: ResolvedTypography): Float = when {
        p?.indentEm != null -> p.indentEm * typo.fontPx
        typo.indentPx > 0f -> typo.indentPx
        else -> 0f
    }

    // 五期: 行内图片显示尺寸——高约 1.2 倍字号、宽等比;上限 1.6 倍字号/版心宽(通用行内图防撑爆);
    // 坏图(bounds null)按一个字宽方框占位
    internal fun inlineImageDisplaySize(bounds: Rect?, typo: ResolvedTypography): ImageSize {
        val maxH = (typo.fontPx * 1.6f).roundToInt().coerceAtLeast(1)
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            val s = typo.fontPx.roundToInt().coerceAtLeast(1)
            return ImageSize(s, s)
        }
        var h = (typo.fontPx * 1.2f).roundToInt().coerceAtLeast(1)
        var w = (h.toFloat() * bounds.width() / bounds.height()).roundToInt().coerceAtLeast(1)
        if (h > maxH) {
            h = maxH
            w = (h.toFloat() * bounds.width() / bounds.height()).roundToInt().coerceAtLeast(1)
        }
        if (w > typo.textWidth) {
            w = typo.textWidth
            h = (w.toFloat() * bounds.height() / bounds.width()).roundToInt().coerceAtLeast(1)
        }
        return ImageSize(w, h)
    }

    // 五期: 行内图片占位符的度量 span——只向 StaticLayout 提供真实宽度参与断行,
    // 行高跟随字号(小图标不撑行);draw 为空实现,绘制由自绘管线按占位符位置负责
    private class InlineImageSpan(val w: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                val f = paint.fontMetricsInt
                fm.ascent = f.ascent
                fm.descent = f.descent
                fm.top = f.top
                fm.bottom = f.bottom
                fm.leading = 0
            }
            return w
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }

    // 一期纯投影入口保留(TXT 路径/既有测试使用;与段落版投影逐字符一致)
    fun compose(title: String, body: String, typo: ResolvedTypography): Spanned =
        compose(title, body.split('\n').map { Paragraph(it) }, typo)

    // Run 样式位 → 字符样式 span(度量用;基线偏移/颜色/阴影/字体由绘制层处理)。
    // sizeEm 非空加 RelativeSizeSpan(断行按 run 字号测宽,行高随行内最大字号)
    internal fun applyRunSpan(sb: SpannableStringBuilder, s: Int, e: Int, style: Int, sizeEm: Float? = null) {
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (sizeEm != null && sizeEm != 1f) {
            sb.setSpan(RelativeSizeSpan(sizeEm), s, e, flags)
        }
        val bold = style and RunStyle.BOLD != 0
        val italic = style and RunStyle.ITALIC != 0
        if (bold || italic) {
            val tf = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                else -> Typeface.ITALIC
            }
            sb.setSpan(StyleSpan(tf), s, e, flags)
        }
        if (style and RunStyle.UNDERLINE != 0) sb.setSpan(UnderlineSpan(), s, e, flags)
        if (style and RunStyle.STRIKE != 0) sb.setSpan(StrikethroughSpan(), s, e, flags)
        if (style and (RunStyle.SUP or RunStyle.SUB) != 0) {
            sb.setSpan(RelativeSizeSpan(SUP_SUB_SCALE), s, e, flags)
        }
    }

    // 剥掉正文原生的标题行(章表 startChar 指向标题行首,正文自带标题;
    // 不剥会与合成的大标题、页眉形成三个标题)。返回 [剥后的正文, 剥掉的字符数]。
    // 首个非空行与章名 trim 后不等(无章节书/非标准文本)则原样返回
    fun stripLeadingTitle(body: String, title: String): Pair<String, Int> {
        val t = title.trim()
        if (t.isEmpty()) return body to 0
        var lineStart = 0
        var i = 0
        while (i <= body.length) {
            if (i == body.length || body[i] == '\n') {
                if (body.substring(lineStart, i).trim() == t) {
                    var e = if (i < body.length) i + 1 else i   // 越过标题行换行
                    while (e < body.length && body[e] == '\n') e++  // 标题后紧跟的空行一并剥掉
                    return body.substring(e) to e
                }
                if (body.substring(lineStart, i).isNotBlank()) return body to 0   // 首个非空行不是标题
                lineStart = i + 1
            }
            i++
        }
        return body to 0
    }

    // stripLeadingTitle 的段落级等价(二期富文本入口): 剥掉"首个非空段"若等于章名,
    // 连同其前后的空段一起剥。返回 [剥后的段落, 剥掉的投影字符数](每段贡献 len+1,
    // 段间恰一个 \n,与字符级行为逐字符一致——剥标题前后的空白段也一并剥掉)
    fun stripLeadingTitleParas(paras: List<Paragraph>, title: String): Pair<List<Paragraph>, Int> {
        val t = title.trim()
        if (t.isEmpty()) return paras to 0
        val first = paras.indexOfFirst { it.text.isNotBlank() }
        if (first < 0 || paras[first].text.trim() != t) return paras to 0
        var e = first + 1
        while (e < paras.size && paras[e].text.isBlank()) e++
        var stripped = 0
        for (i in 0 until e) stripped += paras[i].text.length + 1
        return paras.drop(e) to stripped
    }

    // 段首字符是全角/半角空格或制表符 → 源文本自带首行缩进
    internal fun leadingIndented(sb: CharSequence, paraStart: Int): Boolean =
        paraStart < sb.length && (sb[paraStart] == '　' || sb[paraStart] == ' ' || sb[paraStart] == '\n' || sb[paraStart] == '\t')
}

// 分页器: 内容源+版式 → 全量页目录;页目录+章行模型(带缓存) → 可渲染页。
//
// 网格排版: 断行(StaticLayout,含系统禁则)与行布局(行模型)分离——
// 整章只断行一次,行高/段前距量化到网格,页高与版心高的零头天然小于一个网格,
// 页底视觉铺满;行高与段前距全章恒定,相邻页的行位天然对齐,匀齐分摊不再需要
object BookPager {

    // 章行模型缓存: 物化同章页(翻页/邻页预物化)免重复断行。版式/书变化整体失效,
    // 小 LRU 控内存。物化并发(Default 线程)经 synchronized 串行化,首建后命中
    private var cacheTypo: ResolvedTypography? = null
    private var cacheContent: BookContent? = null
    private val lineCache = object : LinkedHashMap<Int, ChapterLines>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterLines>) = size > 4
    }

    // 全书页目录: 封面 + 各章 + 封底 的扁平页序列。
    // 各章断行互相独立(每次调用自建 Paint/StaticLayout,无共享可变状态),按章真并行执行。
    // 断行是大计算,必须在 Default 调度器上——留在主线程(调用方 LaunchedEffect 的调度器)
    // 时,连续改版式触发的重排会积压输入事件导致 ANR。
    // 信号量限流控制同时驻留内存的章断行布局数;awaitAll 按发起顺序取结果,与串行结果一致
    suspend fun buildSpecs(content: BookContent, typo: ResolvedTypography): List<PageSpec> =
        coroutineScope {
            val specs = ArrayList<PageSpec>()
            specs += PageSpec(PageKind.COVER, -1, 0, 1, 0L, "")
            val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val permits = Semaphore(parallelism)
            val perChapter = (0 until content.chapterCount).map { c ->
                async(Dispatchers.Default) {
                    permits.withPermit {
                        try {
                            ensureActive()
                        } catch (t: Throwable) { throw t }
                        try {
                        val cl = putLines(
                            content, c, buildChapterLines(content, c, typo), typo
                        )
                        specsOf(cl, c, content.chapterStart(c), content.chapterTitle(c), typo)
                        } catch (t: Throwable) {
                            android.util.Log.e("BookPager", "chapter $c failed", t)
                            throw t
                        }
                    }
                }
            }
            try {
                perChapter.awaitAll().forEach { specs += it }   // 发起顺序 = 章序,结果确定性不变
            } catch (t: Throwable) {
                android.util.Log.e("BookPager", "buildSpecs failed", t)
                throw t
            }
            specs += PageSpec(PageKind.BACK, content.chapterCount, 0, 1, content.totalChars, "")
            specs
        }

    // 章 → 行模型。断行度量来自固定字体度量,与传入 layout 的行序结合生成行高。
    // 仅在此处构建 StaticLayout,断行结果入缓存后 layout 即弃。
    // 二期: 段落级剥标题 + Run span 度量 + 段落区间表 + 图片行占位修正
    private fun buildChapterLines(
        content: BookContent,
        chapterIndex: Int,
        typo: ResolvedTypography
    ): ChapterLines {
        val doc = content.chapterDoc(chapterIndex)
        val title = content.chapterTitle(chapterIndex)
        val (paras, stripped) = ChapterComposer.stripLeadingTitleParas(doc.paragraphs, title)

        val composed = ChapterComposer.compose(title, paras, typo, imageBounds = { ref -> content.imageBounds(ref) })
        val bodyStart = ChapterComposer.bodyStart(title.length)
        val measure = Typography.buildLayout(composed, typo)
        val bodyFm = Paint.FontMetrics()
        val titleFm = Paint.FontMetrics()
        val tp = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
        tp.textSize = typo.fontPx
        tp.getFontMetrics(bodyFm)
        tp.textSize = typo.fontPx * ChapterComposer.TITLE_SCALE
        tp.getFontMetrics(titleFm)
        val bodyAscentAbs = -bodyFm.ascent.toInt()
        val titleAscentAbs = -titleFm.ascent.toInt()
        val bodyNatural = (bodyFm.descent - bodyFm.ascent).toInt().toFloat()
        val titleNatural = (titleFm.descent - titleFm.ascent).toInt().toFloat()
        val paraAbove = LineGrid.paraAbove(typo.paraExtraPx, Typography.GRID_PX)
        // 满页排版: 正文行高放大贴合版心(每页固定行数,页底余数缩到网格级;
        // 全章行高一致,跨页行位对齐保持)。标题行/空行/图片行不参与放大
        val bodyPitch = PaginationEngine.fitPitch(
            typo.textHeight,
            LineGrid.linePitch(bodyNatural, typo.lineExtraPx, Typography.GRID_PX),
            Typography.GRID_PX
        )
        // 行距空白上下对分: 行框空白一半移到文字上方,页面上下留白对称
        val titlePitch = LineGrid.linePitch(titleNatural, typo.lineExtraPx, Typography.GRID_PX)
        val bodyShift = LineGrid.centerShift(bodyPitch, bodyNatural)
        val titleShift = LineGrid.centerShift(titlePitch, titleNatural)
        // 段落区间表(正文区,不含段间换行;与 paras 对齐,空段为空区间)
        // + 段前额外距: 书内 margin(段自身 above + 前段 below,不折叠简单叠加;开关关闭全零)
        // 七期: margin 支持 em/px/%(百分比相对版心宽),此处直接折 px
        val paraRanges = ArrayList<IntRange>(paras.size)
        val extraAbove = FloatArray(paras.size)
        run {
            var p = bodyStart
            var prevBelowPx = 0f
            val tw = typo.textWidth.toFloat()
            for ((i, para) in paras.withIndex()) {
                paraRanges += p until (p + para.text.length)
                var extra = 0f
                if (typo.bookSpacing) {
                    val abovePx = para.spaceAboveEm?.px(typo.fontPx, tw) ?: 0f
                    // 七期补: <br/> 相邻段是同段内强制换行,段距归零;
                    // CSS 折叠语义: 同为正取较大值,含负值相加(正负抵消,允许净负上提)
                    // 装饰盒内段距归零(对照浏览器/Calibre 渲染: 盒内密度由 line-height 控制,
                    // 正的段 margin 不产生额外缝——否则内容撑出气泡底);
                    // 负 margin(上提,如 t-box3 红1标签 -1.7em)是设计语义,保留生效
                    extra = when {
                        para.brBefore -> 0f
                        para.boxStyle != null -> abovePx.coerceAtMost(0f)
                        abovePx < 0f || prevBelowPx < 0f -> prevBelowPx + abovePx
                        else -> maxOf(prevBelowPx, abovePx)
                    }
                }
                extraAbove[i] = extra
                prevBelowPx = if (typo.bookSpacing && !para.brBefore) (para.spaceBelowEm?.px(typo.fontPx, tw) ?: 0f) else 0f
                p += para.text.length + 1
            }
        }

        // 七期: 段级左右度量(整段缩进/定宽)与右缩进段独立断行。
        // 右缩进(margin-right 或定宽推出的右留白)使主 layout 全宽断行过宽,
        // 这些段用独立 StaticLayout(宽 = 可用宽)断行,行区间替换主行
        fun paraMetricsOf(p: Paragraph?): ParaMetrics = paraMetrics(p, typo)
        val overrides = HashMap<Int, List<IntRange>>()
        val tableLayouts = HashMap<Int, TableLayout>()
        val tableRowOfLine = HashMap<Int, Int>()
        run {
            for ((pi, para) in paras.withIndex()) {
                if (para.isImage) continue   // 图片段单行,不参与独立断行(对齐由物化层处理)
                // 表格段: 布局一次(列宽/行高/格折行),虚拟行 = 表格行(每行 pitch = 行高),分页引擎按行切页天然断表
                if (para.isTable) {
                    val td = para.table ?: continue
                    val pm = paraMetricsOf(para)
                    val measurePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = typo.fontPx }
                    val layout = layoutTable(td, pm.availWidth, typo) { s -> measurePaint.measureText(s) }
                    tableLayouts[pi] = layout
                    val rng = paraRanges[pi]
                    if (!rng.isEmpty()) {
                        val rows = ArrayList<IntRange>(layout.heights.size)
                        repeat(layout.heights.size) { rows += rng.first until rng.last + 1 }   // 全部行同一 U+FFFC 占位
                        overrides[pi] = rows
                    }
                    continue
                }
                if (para.breakAll) {
                    // 批次四e: word-break:break-all——手动逐字折行(空格优先,超长硬断),
                    // 行区间直接产出(绘制走普通行路径,折行宽度 = 可用宽 - 首行缩进)
                    val rngB = paraRanges[pi]
                    if (!rngB.isEmpty()) {
                        val pmB = paraMetricsOf(para)
                        val indentB = ChapterComposer.paraIndentPx(para, typo)
                        val innerW = (pmB.availWidth - indentB).coerceAtLeast(typo.fontPx)
                        val measurePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = typo.fontPx }
                        val tmp = TableCell(0, 0, text = para.text, runs = para.runs)
                        val wrapped = wrapCellText(tmp, innerW, typo) { st -> measurePaint.measureText(st) }
                        var off = 0
                        val rows = ArrayList<IntRange>(wrapped.size)
                        for (wl in wrapped) {
                            rows += (rngB.first + wl.startOff) until (rngB.first + wl.startOff + wl.text.length).coerceAtMost(rngB.last + 1)
                            off += wl.text.length
                        }
                        if (rows.isNotEmpty()) overrides[pi] = rows
                    }
                    continue
                }
                val pm = paraMetricsOf(para)
                if (!pm.needsOverride) continue
                val rng = paraRanges[pi]
                if (rng.isEmpty()) continue
                val sl = overrideLayout(para, pm, typo)
                val rows = ArrayList<IntRange>(sl.lineCount)
                for (i in 0 until sl.lineCount) {
                    val rs = sl.getLineStart(i)
                    val re = sl.getLineEnd(i)
                    if (re > rs) {
                        rows += (rng.first + rs) until (rng.first + re).coerceAtMost(rng.last + 1)
                    }
                }
                if (rows.isNotEmpty()) overrides[pi] = rows
            }
        }

        // 批次四b: float 盒真环绕——带 width+height 的浮动盒:
        //   盒段自身按盒宽独立断行;盒行范围内的后续段落缩窄(版心-盒宽)断行,
        //   物化时行 x 偏移盒宽(盒在左)。避让行数 = max(盒段实际行数, 固定高/行高);
        //   无 width/height 的 float 保持六期右对齐降级
        val avoidX = HashMap<Int, Float>()
        run {
            for ((piF, paraF) in paras.withIndex()) {
                if (paraF.floatSide == 0 || paraF.widthEm == null) continue
                val hCss = paraF.boxStyle?.heightCss ?: continue
                val pmF = paraMetrics(paraF, typo)
                val boxW = pmF.availWidth
                if (boxW <= 0f || boxW >= typo.textWidth * 0.8f) continue   // 盒过宽不环绕
                val rngF = paraRanges[piF]
                if (rngF.isEmpty()) continue
                val slF = overrideLayout(paraF, pmF, typo)
                val rowsF = ArrayList<IntRange>(slF.lineCount)
                for (i in 0 until slF.lineCount) {
                    val rs = slF.getLineStart(i); val re = slF.getLineEnd(i)
                    if (re > rs) rowsF += (rngF.first + rs) until (rngF.first + re).coerceAtMost(rngF.last + 1)
                }
                if (rowsF.isEmpty()) continue
                overrides[piF] = rowsF
                val li0 = (0 until measure.lineCount).firstOrNull { measure.getLineStart(it) == rngF.first } ?: continue
                val hPx = hCss.px(typo.fontPx, typo.textWidth.toFloat())
                val nBox = maxOf(rowsF.size, kotlin.math.ceil(hPx / bodyPitch).toInt().coerceAtLeast(1))
                val avoidEnd = li0 + nBox
                for ((pi, para) in paras.withIndex()) {
                    if (pi == piF || para.isImage || para.isTable || para.isFloat) continue
                    val rng = paraRanges[pi]
                    if (rng.isEmpty()) continue
                    val liS = (0 until measure.lineCount).firstOrNull { measure.getLineStart(it) == rng.first } ?: continue
                    val liE = (0 until measure.lineCount).lastOrNull { measure.getLineStart(it) <= rng.last } ?: liS
                    if (liS >= avoidEnd) break
                    if (liE < li0) continue
                    val pmA = paraMetrics(para, typo)
                    val narrow = pmA.copy(availWidth = (pmA.availWidth - boxW).coerceAtLeast(typo.fontPx))
                    val sl = overrideLayout(para, narrow, typo)
                    val rows = ArrayList<IntRange>(sl.lineCount)
                    for (i in 0 until sl.lineCount) {
                        val rs = sl.getLineStart(i); val re = sl.getLineEnd(i)
                        if (re > rs) rows += (rng.first + rs) until (rng.first + re).coerceAtMost(rng.last + 1)
                    }
                    if (rows.isNotEmpty()) {
                        overrides[pi] = rows
                        avoidX[pi] = if (paraF.floatSide == 2) boxW else 0f
                    }
                }
            }
        }

        // BODY 行的段级行距与基线: 无书内行距用全局贴合行高;有则行框 = 字号×倍率(CSS line-height 语义,
        // 覆盖全局行距),行框空白(可负,如 0% 压行)上下对分。
        // CSS line-height 的 em 相对段落自身字号: 取段内最大 run 缩放字号(无 run 用全局)。
        // 装饰盒内段落(书内 CSS 排版)无 line-height 时行高用 normal(bodyNatural),
        // 不吃全局贴合行距/行距偏好——盒的密度由书内 CSS 控制
        fun paraLinePitch(para: Paragraph?): Pair<Int, Int> {
            val lh = para?.lineSpacingMult
            if (lh == null) {
                if (para?.boxStyle != null) return bodyNatural.toInt() to bodyAscentAbs
                return bodyPitch to (bodyAscentAbs + bodyShift)
            }
            val basePx = para.runs.firstOrNull()?.sizeEm?.let { typo.fontPx * it } ?: typo.fontPx
            val frame = basePx * lh
            val p = PaginationEngine.gridCeil(frame, Typography.GRID_PX).coerceAtLeast(Typography.GRID_PX)
            return p to (bodyAscentAbs + LineGrid.centerShift(p, bodyNatural))
        }

        val lines = ArrayList<TextLine>(measure.lineCount)
        var rangeCursor = 0
        val emittedOverride = HashSet<Int>()
        for (i in 0 until measure.lineCount) {
            var s = measure.getLineStart(i)
            var e = measure.getLineEnd(i)
            if (e > s && composed[e - 1] == '\n') e--
            val kind = lineKind(composed, s, e, bodyStart)
            val isParaStart = kind == LineKind.BODY && paraStart(composed, s, bodyStart)
            // 段首行: 叠加书内 margin 折算的段前额外距(网格化)
            if (isParaStart) {
                while (rangeCursor < paraRanges.size && paraRanges[rangeCursor].first != s) rangeCursor++
            }
            // 行所属段(rangeCursor 已指向段首所在段;段中行不推进)
            val curPi = if (rangeCursor < paraRanges.size && s >= paraRanges[rangeCursor].first &&
                s <= paraRanges[rangeCursor].last
            ) rangeCursor else -1
            // 右缩进段: 首个主行位置展开独立断行行,该段其余主行丢弃
            if (curPi >= 0 && curPi in overrides) {
                if (emittedOverride.add(curPi)) {
                    val para = paras[curPi]
                    for ((j, r) in (overrides[curPi] ?: emptyList()).withIndex()) {
                        val isStart = j == 0
                        // 装饰盒内段落负上提放宽到两倍段前距(书内负 margin 是设计语义)
                        val negLimitO = if (paras.getOrNull(curPi)?.boxStyle != null) -paraAbove * 2 else -paraAbove
                        val bookGrid = if (isStart) {
                            PaginationEngine.gridCeil(extraAbove[curPi], Typography.GRID_PX).coerceAtLeast(negLimitO)
                        } else 0
                        // 表格行: pitch = 表格行高网格化(基线偏移零,绘制从行顶);普通右缩进段: 段级行距
                        if (para.isTable) {
                            val tl = tableLayouts[curPi]!!
                            val pitch = PaginationEngine.gridCeil(tl.heights[j], Typography.GRID_PX)
                            val above = if (isStart) paraAbove + bookGrid else 0
                            lines += TextLine(r.first, r.last + 1, LineKind.BODY, isStart, above + pitch, above, 0)
                            tableRowOfLine[lines.lastIndex] = j
                        } else {
                            val (bp, ba) = paraLinePitch(para)
                            // <br/> 相邻段: 段前距归零(与主路径同规则)
                            // 盒内段落不吃全局段距(装饰排版密度由书内 CSS 控制)
                            val above2 = if (isStart) {
                                when {
                                    para.brBefore -> 0
                                    para.boxStyle != null -> bookGrid
                                    else -> paraAbove + bookGrid
                                }
                            } else 0
                            lines += TextLine(r.first, r.last + 1, LineKind.BODY, isStart, above2 + bp, above2, ba)
                        }
                    }
                }
                continue
            }
            val bookExtra = if (isParaStart && rangeCursor < extraAbove.size) extraAbove[rangeCursor] else 0f
            // 六期 A2: 负 margin 最多抵消全局段前距(不侵蚀行高本体,防文字重叠);
            // 装饰盒内段落放宽到两倍段前距(负上提是书内设计语义)
            val negLimit = if (rangeCursor < paras.size && paras[rangeCursor].boxStyle != null) -paraAbove * 2 else -paraAbove
            val bookGrid = PaginationEngine.gridCeil(bookExtra, Typography.GRID_PX)
                .coerceAtLeast(negLimit)
            val curPara = if (curPi >= 0 && curPi < paras.size) paras[curPi] else null
            val (pitch, ascentAbs) = when (kind) {
                LineKind.BLANK -> LineGrid.blankPitch(Typography.GRID_PX) to bodyAscentAbs
                LineKind.TITLE ->
                    // 装饰章(首段带装饰盒)不显示阅读器章名行——原书页面没有它,
                    // 它会把整个装饰版面往下挤;行保留为零占位以维持文本投影轴
                    if (paras.firstOrNull()?.boxStyle != null) 0 to bodyAscentAbs
                    else titlePitch to (titleAscentAbs + titleShift)
                LineKind.BODY -> {
                    val (bp, ba) = paraLinePitch(curPara)
                    // <br/> 相邻段: 连全局段前距一并跳过(br 是同段内紧凑换行)
                    val above = if (isParaStart) {
                        when {
                            curPara?.brBefore == true -> 0
                            curPara?.boxStyle != null -> bookGrid
                            else -> paraAbove + bookGrid
                        }
                    } else 0
                    above + bp to ba
                }
            }
            lines += TextLine(
                s, e, kind, isParaStart, pitch,
                if (isParaStart) {
                    when {
                        curPara?.brBefore == true -> 0
                        curPara?.boxStyle != null -> bookGrid
                        else -> paraAbove + bookGrid
                    }
                } else 0,
                ascentAbs
            )
        }

        // 批次四c: 固定高盒——盒组内容高 < 固定高时,内容整体下移(垂直居中):
        // 首行段前距与各行基线加偏移;矩形高由物化层取 max(内容高, 固定高)
        run {
            var gp = 0
            while (gp < paras.size) {
                val box = paras[gp].boxStyle
                val hCss = box?.heightCss
                if (hCss == null) { gp++; continue }
                var last = gp
                while (last + 1 < paras.size && paras[last + 1].boxStyle == box) last++
                val firstIdx = lines.indexOfFirst { it.isParaStart && it.start == paraRanges[gp].first }
                val lastIdx = lines.indexOfLast { it.start >= paraRanges[gp].first && it.start <= paraRanges[last].first }
                if (firstIdx >= 0 && lastIdx >= firstIdx) {
                    val hPx = hCss.px(typo.fontPx, typo.textWidth.toFloat())
                    var contentH = 0f
                    for (li in firstIdx..lastIdx) contentH += lines[li].pitch
                    if (hPx > contentH) {
                        val shift = (hPx - contentH) / 2f
                        val shiftI = Math.round(shift)
                        for (li in firstIdx..lastIdx) {
                            val ln = lines[li]
                            val above = ln.paraAbove + if (li == firstIdx) shiftI else 0
                            lines[li] = TextLine(
                                ln.start, ln.end, ln.kind, ln.isParaStart,
                                ln.pitch + if (li == firstIdx) shiftI else 0, above,
                                ln.ascentAbs + shiftI
                            )
                        }
                    }
                }
                gp = last + 1
            }
        }

        // 图片行占位: 按 U+FFFC 单字符定位该段的行,按版心宽等比换算显示尺寸,
        // 行高替换为"段前距 + 图片高"(网格化;超高图缩到一页内),基线偏移清零(行顶绘制)
        val imageSizes = HashMap<Int, ImageSize>()
        for ((pi, para) in paras.withIndex()) {
            if (!para.isImage) continue
            val rng = paraRanges[pi]
            val li = lines.indexOfFirst { it.start == rng.first && it.end == rng.first + 1 }
            if (li < 0) continue
            val bounds = content.imageBounds(para.imageRef ?: "")
            // 七期批次三: style/class width 定宽(有效范围 [1, 版心宽]),等比缩放
            val targetW = para.widthEm?.px(typo.fontPx, typo.textWidth.toFloat())?.roundToInt()
                ?.takeIf { it in 1..typo.textWidth }
            val size = if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
                ImageSize(targetW ?: typo.textWidth, typo.fontPx.toInt() * 3)   // 坏图占位 3 行高,可见可感知
            } else {
                val w = targetW ?: typo.textWidth
                var h = (w.toFloat() * bounds.height() / bounds.width()).roundToInt().coerceAtLeast(1)
                var dw = w
                if (h > typo.textHeight) {
                    h = typo.textHeight
                    dw = (h.toFloat() * bounds.width() / bounds.height()).roundToInt().coerceAtLeast(1)
                }
                ImageSize(dw, h)
            }
            imageSizes[pi] = size
            val old = lines[li]
            // 六期 A2: 负 margin 同样 clamp(不侵蚀行高)
            val bookExtraGrid = PaginationEngine.gridCeil(extraAbove[pi], Typography.GRID_PX)
                .coerceAtLeast(-paraAbove)
            val imgPitch = old.paraAbove + bookExtraGrid + PaginationEngine.gridCeil(size.height.toFloat(), Typography.GRID_PX)
            lines[li] = TextLine(old.start, old.end, old.kind, old.isParaStart, imgPitch, old.paraAbove + bookExtraGrid, 0)
        }

        // 五期: 行内图片显示尺寸预取(断行时算好,物化直接查;compose 的 ReplacementSpan 同源同值)
        val inlineSizes = HashMap<String, ImageSize>()
        if (paras.any { it.inlineImages.isNotEmpty() }) {
            for (p in paras) {
                for (im in p.inlineImages) {
                    if (im.ref !in inlineSizes) {
                        inlineSizes[im.ref] = ChapterComposer.inlineImageDisplaySize(content.imageBounds(im.ref), typo)
                    }
                }
            }
        }

        return ChapterLines(
            composed, bodyStart, content.chapterStart(chapterIndex) + stripped, lines,
            paras, paraRanges, imageSizes, inlineSizes, overrides, doc.fontIds, doc.fontFiles,
            tableLayouts, tableRowOfLine, avoidX
        )
    }

    // 七期: 段级左右度量(px)。定宽段推出右留白(左右 auto 时整体居中);
    // 右缩进 > 0.5px 的段需独立断行(主 layout 全宽断行会过宽)
    internal data class ParaMetrics(val mlPx: Float, val mrPx: Float, val availWidth: Float, val needsOverride: Boolean)

    internal fun paraMetrics(p: Paragraph?, typo: ResolvedTypography): ParaMetrics {
        val tw = typo.textWidth.toFloat()
        if (p == null) return ParaMetrics(0f, 0f, tw, false)
        val fontPx = typo.fontPx
        var ml = p.marginLeftEm?.px(fontPx, tw) ?: 0f
        var mr = p.marginRightEm?.px(fontPx, tw) ?: 0f
        val w = p.widthEm?.px(fontPx, tw)
        if (w != null && w < tw) {
            when {
                p.floatSide == 1 -> { ml = tw - w; mr = 0f }   // float:right: 盒贴右缘
                p.floatSide == 2 -> { ml = 0f; mr = tw - w }   // float:left: 盒靠左缘
                p.widthAlign == 1 -> { ml = (tw - w) / 2f; mr = tw - ml - w }
                p.widthAlign == 2 -> { ml = tw - w; mr = 0f }  // 左 auto: 贴右缘
                p.widthAlign == 3 -> { ml = 0f; mr = tw - w }  // 右 auto: 靠左缘
                else -> mr = (tw - ml - w).coerceAtLeast(0f)
            }
        }
        // 防病态: 缩进不吞没行;盒定位场景(float/贴边)的偏移天然占版心大半,放宽到 85%
        val cap = if (p.floatSide != 0 || p.widthAlign == 2 || p.widthAlign == 3) 0.85f else 0.45f
        ml = ml.coerceIn(0f, tw * cap)
        mr = mr.coerceIn(0f, tw * cap)
        return ParaMetrics(ml, mr, tw - ml - mr, mr > 0.5f)
    }

    // ---------- 表格布局(七期批次四) ----------

    // 列宽: 内容自然宽按 colSpan 均摊取列最大;总宽不足版心按比例摊余量撑满,
    // 超出版心按比例压缩(保底 30% 版心)。行高: 单行格取需求最大,跨行格差额均摊到覆盖行。
    // 格内文本贪心折行(空格优先断,超长硬断),runs 折算为行相对 LineStyle
    internal fun layoutTable(
        td: TableData,
        availWidth: Float,
        typo: ResolvedTypography,
        measure: (String) -> Float
    ): TableLayout {
        val fontPx = typo.fontPx
        val padH = fontPx * 0.3f
        val padV = fontPx * 0.18f
        val gap = if (td.collapse) 0f else td.spacingEm * fontPx
        val lineH = fontPx * 1.35f
        // 1. 列宽: td width 提示优先(固定),未提示列按内容自然宽;总宽超版心整体压缩
        val widths = FloatArray(td.cols)
        val hinted = BooleanArray(td.cols)
        td.colWidths.forEachIndexed { c, hint ->
            if (c < td.cols && hint != null) {
                widths[c] = hint.px(fontPx, availWidth).coerceAtLeast(1f)
                hinted[c] = true
            }
        }
        for (cell in td.cells) {
            val boldFactor = if (cell.header) 1.06f else 1f
            val natural = measure(cell.text) * boldFactor + padH * 2f
            val free = (cell.col until minOf(cell.col + cell.colSpan, td.cols)).filter { !hinted[it] }
            if (free.isEmpty()) continue
            val per = natural / free.size
            for (c in free) widths[c] = maxOf(widths[c], per)
        }
        val gapTotal = gap * (td.cols + 1)
        // 批次四修复: 提示列(td width)固定,余量/压缩只作用于未提示列——
        // 原书列宽比例(如登场人物表 8.5em/14em)不被内容长短打乱
        val anyHint = td.colWidths.any { it != null }
        if (anyHint) {
            val freeSum = (widths.indices).filter { !hinted[it] }.sumOf { widths[it].toDouble() }.toFloat()
            val budget = (availWidth - gapTotal - widths.filterIndexed { c, _ -> hinted[c] }.sum())
                .coerceAtLeast(typo.textWidth * 0.15f)
            if (freeSum > 0f) {
                if (freeSum > budget) {
                    val scale = budget / freeSum
                    for (c in widths.indices) if (!hinted[c]) widths[c] *= scale
                } else if (freeSum > 0f && budget > freeSum) {
                    val extra = budget - freeSum
                    for (c in widths.indices) if (!hinted[c]) widths[c] += extra * widths[c] / freeSum
                }
            }
        } else {
            val naturalSum = widths.sum() + gapTotal
            if (naturalSum < availWidth && widths.sum() > 0f) {
                val extra = availWidth - naturalSum
                val wsum = widths.sum()
                for (c in widths.indices) widths[c] += extra * widths[c] / wsum
            } else if (naturalSum > availWidth) {
                val scale = (availWidth - gapTotal).coerceAtLeast(typo.textWidth * 0.3f) /
                    (naturalSum - gapTotal).coerceAtLeast(1f)
                for (c in widths.indices) widths[c] *= scale
            }
        }
        // 列 x 坐标
        val colX = FloatArray(td.cols)
        run { var acc = gap; for (c in 0 until td.cols) { colX[c] = acc; acc += widths[c] + gap } }
        // 2. 格内容折行与需求高
        data class Prepared(val cell: TableCell, val x: Float, val innerW: Float, val lines: List<CellTextLine>, val needH: Float)
        val prepared = ArrayList<Prepared>(td.cells.size)
        for (cell in td.cells) {
            val lastCol = minOf(cell.col + cell.colSpan, td.cols)
            val outerW = widths.copyOfRange(cell.col, lastCol).sum()
            val innerW = (outerW - padH * 2f).coerceAtLeast(fontPx)
            val lines = wrapCellText(cell, innerW, typo, measure)
            val needH = lines.size * lineH + padV * 2f
            prepared += Prepared(cell, colX[cell.col] + padH, innerW, lines, needH)
        }
        // 3. 行高: 单行格先行,跨行格补差
        val heights = FloatArray(td.rows) { 0f }
        for (p in prepared) if (p.cell.rowSpan == 1) heights[p.cell.row] = maxOf(heights[p.cell.row], p.needH)
        for (p in prepared) {
            if (p.cell.rowSpan == 1) continue
            val r0 = p.cell.row
            val r1 = minOf(p.cell.row + p.cell.rowSpan, td.rows)
            var spanned = 0f
            for (r in r0 until r1) spanned += heights[r]
            if (spanned < p.needH) {
                val per = (p.needH - spanned) / (r1 - r0)
                for (r in r0 until r1) heights[r] += per
            }
        }
        for (r in heights.indices) heights[r] = maxOf(heights[r], lineH + padV * 2f)
        // 行 y
        val rowY = FloatArray(td.rows)
        run { var accY = gap; for (r in 0 until td.rows) { rowY[r] = accY; accY += heights[r] + gap } }
        // 4. 格几何与垂直对齐
        val cellBoxes = ArrayList<TableCellBox>(prepared.size)
        for (p in prepared) {
            val r0 = p.cell.row
            val r1 = minOf(p.cell.row + p.cell.rowSpan, td.rows)
            var spannedH = 0f
            for (r in r0 until r1) spannedH += heights[r] + (if (r > r0) gap else 0f)
            val textH = p.lines.size * lineH
            val yOff = when (p.cell.vAlign) {
                0 -> padV
                2 -> (spannedH - textH - padV).coerceAtLeast(padV)
                else -> (spannedH - textH) / 2f
            }
            cellBoxes += TableCellBox(p.cell, p.x, rowY[r0] + yOff, p.innerW, spannedH, p.lines)
        }
        val totalW = widths.sum() + gapTotal
        val totalH = rowY.lastOrNull()?.plus(heights.lastOrNull() ?: 0f)?.plus(gap) ?: 0f
        return TableLayout(widths.toList(), heights.toList(), rowY.toList(), cellBoxes, totalW, totalH, padH, padV, gap, lineH)
    }

    // 格内贪心折行: 空格处优先断(断后空格丢弃),无空格超长按字符硬断(CJK 逐字天然可断);
    // runs 裁剪为行相对 LineStyle
    internal fun wrapCellText(
        cell: TableCell,
        innerW: Float,
        typo: ResolvedTypography,
        measure: (String) -> Float
    ): List<CellTextLine> {
        val text = cell.text
        if (text.isEmpty()) return emptyList()
        val lines = ArrayList<CellTextLine>()
        var lineStart = 0
        var w = 0f
        var lastBreak = -1
        var i = 0
        fun emit(from: Int, until: Int) {
            val seg = text.substring(from, until).trimEnd()
            if (seg.isEmpty()) return
            val off = from
            val styles = ArrayList<LineStyle>()
            for (run in cell.runs) {
                val a = maxOf(run.start, off)
                val b2 = minOf(run.end, off + seg.length)
                if (a < b2) styles += LineStyle(a - off, b2 - off, run.style, run.sizeEm, run.color, run.shadow, null)
            }
            lines += CellTextLine(seg, off, styles)
        }
        while (i < text.length) {
            val cw = measure(text[i].toString())
            if (w + cw > innerW && i > lineStart) {
                if (lastBreak >= lineStart) {
                    emit(lineStart, lastBreak + 1)
                    lineStart = lastBreak + 1
                    while (lineStart < text.length && text[lineStart] == ' ') lineStart++
                    i = lineStart
                    w = 0f
                    lastBreak = -1
                    continue
                }
                emit(lineStart, i)
                lineStart = i
                w = 0f
                continue
            }
            w += cw
            if (text[i] == ' ') lastBreak = i
            i++
        }
        if (lineStart < text.length) emit(lineStart, text.length)
        return lines
    }

    // 右缩进/定宽段的独立断行 layout: 宽 = 可用宽,首行缩进由 leading margin 承担,
    // 对齐跟随段落(居中/右对齐在可用宽内)。与主 layout 同一 paint/break strategy,断行确定性一致
    private fun overrideLayout(para: Paragraph, pm: ParaMetrics, typo: ResolvedTypography): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = typo.fontPx }
        val csb = SpannableStringBuilder(para.text)
        // 修复: 补齐 run 字号/样式 span——缺 span 时按 1.0 字号量宽、绘制按实际字号画,
        // 定宽盒内大字号标题因此溢出盒缘(七期验证发现的溢出根因)
        for (run in para.runs) {
            if (run.end > run.start) ChapterComposer.applyRunSpan(csb, run.start, run.end, run.style, run.sizeEm)
        }
        val indentPx = ChapterComposer.paraIndentPx(para, typo)
        if (indentPx > 0f && para.align != 1 && para.align != 2) {
            csb.setSpan(
                LeadingMarginSpan.Standard(indentPx.toInt(), 0), 0, csb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val alignment = when (para.align) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder
            .obtain(csb, 0, csb.length, paint, pm.availWidth.toInt().coerceAtLeast(1))
            .setAlignment(alignment)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .build()
    }

    // 行分类: 全空白行(含标题块后的空行)→ BLANK;正文区之前的非空行 → TITLE;其余 → BODY
    internal fun lineKind(composed: CharSequence, s: Int, e: Int, bodyStart: Int): LineKind = when {
        s >= e -> LineKind.BLANK
        (s until e).all { composed[it].isWhitespace() } -> LineKind.BLANK
        s < bodyStart -> LineKind.TITLE
        else -> LineKind.BODY
    }

    // 段落首行: 正文区内且行首紧邻换行(或为正文区首)
    internal fun paraStart(composed: CharSequence, s: Int, bodyStart: Int): Boolean =
        s >= bodyStart && (s == bodyStart || composed[s - 1] == '\n')

    // 章 → 页窗口: 网格化行高切页(页首首个非空行豁免段前距)+ 裁掉尾部空白页。
    // 装饰章(章首段带装饰盒): 整章一页——页面按快照位图整体呈现,内容不跨页切分
    internal fun paginate(cl: ChapterLines, typo: ResolvedTypography): List<PageSlice> =
        if (isDecorative(cl)) {
            listOf(PageSlice(0, cl.lines.size))
        } else {
            PaginationEngine.trimTrailingBlank(
                PaginationEngine.splitPages(cl.lines, typo.textHeight)
            ) { w -> (w.startLine until w.endLineExclusive).all { cl.lines[it].kind == LineKind.BLANK } }
        }

    // 装饰章判定(章模型,纯函数): 章首段带装饰盒即 CSS 排版页
    internal fun isDecorative(cl: ChapterLines): Boolean = cl.paras.firstOrNull()?.boxStyle != null

    // 页窗口 → 页描述。页首行在标题区内(章首页)锚定章起点;正文页从正文零点换算
    private fun specsOf(
        cl: ChapterLines,
        chapterIndex: Int,
        chapterStartGlobal: Long,
        title: String,
        typo: ResolvedTypography
    ): List<PageSpec> {
        val windows = paginate(cl, typo)
        return windows.mapIndexed { i, w ->
            val s = cl.lines[w.startLine].start
            PageSpec(
                PageKind.CONTENT, chapterIndex, i, windows.size,
                globalOffset(s, cl.bodyStart, cl.bodyZero, chapterStartGlobal), title
            )
        }
    }

    // 合成文本偏移 → 全书偏移(纯函数,单测覆盖): 标题区内锚定章起点
    internal fun globalOffset(s: Int, bodyStart: Int, bodyZero: Long, chapterStartGlobal: Long): Long =
        if (s < bodyStart) chapterStartGlobal else bodyZero + (s - bodyStart)

    // 物化一页。页 = 行窗口(与 buildSpecs 同一 paginate,页界一致),
    // 基线/缩进/两端对齐拉伸在物化时一次算好,绘制零计算。
    // 拖拽预览与落账共用物化结果,保证看到的==翻到的
    // globalPageIndex/globalPageCount: 全书页序(页脚与跳页弹窗同一套页码);
    // 缺省(-1)时页脚退回章内页码
    fun materialize(
        content: BookContent,
        spec: PageSpec,
        typo: ResolvedTypography,
        globalPageIndex: Int = -1,
        globalPageCount: Int = -1
    ): BookPage {
        if (spec.kind != PageKind.CONTENT) {
            if (spec.kind == PageKind.COVER) {
                val cover = content.coverPath
                if (!cover.isNullOrEmpty() && java.io.File(cover).exists()) {
                    // 封面页升级: 有封面画图(版心内等比居中,超高图缩到版心高内),无封面回退文字
                    val bounds = content.imageBounds(cover)
                    if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                        var dw = typo.textWidth
                        var dh = (dw.toFloat() * bounds.height() / bounds.width()).roundToInt().coerceAtLeast(1)
                        if (dh > typo.textHeight) {
                            dh = typo.textHeight
                            dw = (dh.toFloat() * bounds.width() / bounds.height()).roundToInt().coerceAtLeast(1)
                        }
                        return BookPage(spec, "", "", coverImage = cover, coverWidth = dw, coverHeight = dh)
                    }
                }
            }
            val text = if (spec.kind == PageKind.COVER) content.bookTitle else "最后一页了"
            return BookPage(spec, "", "", virtualLayout = Typography.buildVirtualLayout(text, typo))
        }
        val idx = spec.chapterIndex.coerceIn(0, content.chapterCount - 1)
        // 装饰章: 整页快照位图(尺寸按版心宽等比换算,超高缩到一页内;与封面页同一 contain 语义)。
        // 快照未生成时返回 null 走下方近似排版占位,生成完成后调用方重物化换真身
        content.decoSnapshot(idx)?.let { path ->
            val bounds = content.imageBounds(path)
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                var dw = typo.textWidth
                var dh = (dw.toFloat() * bounds.height() / bounds.width()).roundToInt().coerceAtLeast(1)
                if (dh > typo.textHeight) {
                    dh = typo.textHeight
                    dw = (dh.toFloat() * bounds.width() / bounds.height()).roundToInt().coerceAtLeast(1)
                }
                val percent = percentOf(content, spec)
                val pagePart =
                    if (globalPageCount > 0) "${globalPageIndex + 1}/$globalPageCount"
                    else "${spec.chapterPageIndex + 1}/${spec.chapterPageCount}"
                return BookPage(
                    spec, spec.chapterTitle, "$pagePart ${(percent * 100).roundToInt()}%",
                    decoImage = path, decoWidth = dw, decoHeight = dh
                )
            }
        }
        val cl = chapterLines(content, idx, typo)
        val windows = paginate(cl, typo)
        val slice = windows[spec.chapterPageIndex.coerceIn(0, windows.lastIndex)]
        val percent = percentOf(content, spec)
        val pagePart =
            if (globalPageCount > 0) "${globalPageIndex + 1}/$globalPageCount"
            else "${spec.chapterPageIndex + 1}/${spec.chapterPageCount}"
        val label = "$pagePart ${(percent * 100).roundToInt()}%"
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = typo.fontPx }
        val chapterStartGlobal = content.chapterStart(idx)
        val drawn = drawLinesWithBoxes(cl, slice, typo, measure = { paint.measureText(it) }, chapterStartGlobal = chapterStartGlobal)
        return BookPage(
            spec, spec.chapterTitle, label,
            drawn.lines, drawn.boxes, drawn.tables, cl.fontFiles
        )
    }

    // 行窗口 → 可绘制行。跳过空行(其行高已占位);窗口首个非空行豁免段前距(与分页同口径);
    // 段中行做两端对齐拉伸(行尾之后不是换行/章末即为段中行;标题行/段落末行保持自然参差);
    // 图片段产出图片行(无缩进无拉伸,从行顶绘制);普通行折算行内样式段。
    // measure 注入以便本地单测
    internal fun drawLines(
        cl: ChapterLines,
        slice: PageSlice,
        typo: ResolvedTypography,
        measure: (String) -> Float,
        chapterStartGlobal: Long = 0L
    ): List<DrawLine> = drawLinesWithBoxes(cl, slice, typo, measure, chapterStartGlobal).lines

    internal class DrawLinesResult(val lines: List<DrawLine>, val boxes: List<DrawBox>, val tables: List<DrawTable> = emptyList())

    internal fun drawLinesWithBoxes(
        cl: ChapterLines,
        slice: PageSlice,
        typo: ResolvedTypography,
        measure: (String) -> Float,
        chapterStartGlobal: Long = 0L
    ): DrawLinesResult {
        val out = ArrayList<DrawLine>(slice.endLineExclusive - slice.startLine)
        val boxes = ArrayList<DrawBox>()
        val boxRanges = ArrayList<Pair<Int, Int>>()   // 与 boxes 平行: 盒覆盖的段落区间(first..last)
        val tables = ArrayList<DrawTable>()
        var head = slice.startLine
        while (head < slice.endLineExclusive && cl.lines[head].kind == LineKind.BLANK) head++
        // 段落行块区间(页内): 盒组聚合用
        val paraTop = HashMap<Int, Float>()
        val paraBottom = HashMap<Int, Float>()
        val tableWindow = HashMap<Int, Pair<Int, Int>>()   // paraIndex → (firstRow, lastRowExclusive)
        val tableTopY = HashMap<Int, Float>()               // 窗口内表格首行顶 y
        val linePara = ArrayList<Int>()                     // out 每行 → 段落下标(盒基准修正用)
        // 装饰章: 章名行零占位且不绘制
        val hideTitleRow = cl.paras.firstOrNull()?.boxStyle != null
        var y = 0
        for (li in slice.startLine until slice.endLineExclusive) {
            val ln = cl.lines[li]
            val above = if (li == head) 0 else ln.paraAbove
            if (ln.kind != LineKind.BLANK && !(hideTitleRow && ln.kind == LineKind.TITLE)) {
                val global = globalOffset(ln.start, cl.bodyStart, cl.bodyZero, chapterStartGlobal)
                val pi = paraIndexOf(cl, ln.start)
                val para = if (pi >= 0) cl.paras[pi] else null
                val pm = paraMetrics(para, typo)
                paraTop[pi] = minOf(paraTop[pi] ?: Float.MAX_VALUE, (y + above).toFloat())
                paraBottom[pi] = maxOf(paraBottom[pi] ?: 0f, (y + ln.pitch).toFloat())
                // 批次四: 表格行——不产文字行,记录窗口内的表格行号范围,循环后切片
                if (para?.isTable == true) {
                    val rowNo = cl.tableRowOfLine[li] ?: 0
                    val cur = tableWindow[pi]
                    tableWindow[pi] = if (cur == null) rowNo to rowNo + 1 else minOf(cur.first, rowNo) to maxOf(cur.second, rowNo + 1)
                    tableTopY[pi] = minOf(tableTopY[pi] ?: Float.MAX_VALUE, (y + above).toFloat())
                    y += ln.pitch - (if (li == head) ln.paraAbove else 0)
                    continue
                }
                if (para?.isImage == true) {
                    val sz = cl.imageDrawSizes[pi] ?: ImageSize(0, 0)
                    // 四期: 图片行随段级对齐(居中/右对齐;默认贴左);七期: 左缩进基点
                    val imgX = when (para.align) {
                        1 -> pm.mlPx + (pm.availWidth - sz.width) / 2f
                        2 -> pm.mlPx + pm.availWidth - sz.width
                        else -> pm.mlPx
                    }.coerceAtLeast(0f)
                    out += DrawLine(
                        "", imgX, (y + above).toFloat(), false, global,
                        imageRef = para.imageRef,
                        imageWidth = sz.width.toFloat(),
                        imageHeight = sz.height.toFloat()
                    )
                    linePara.add(pi)
                } else {
                    val text = cl.composed.substring(ln.start, ln.end)
                    val align = para?.align ?: 0
                    val avoid = if (pi >= 0) cl.avoidX[pi] ?: 0f else 0f
                    val x: Float
                    val segs: List<LineSeg>?
                    when (align) {
                        1 -> {
                            val w = measure(text)
                            x = (avoid + pm.mlPx + (pm.availWidth - w) / 2f).coerceAtLeast(0f)
                            segs = null
                        }
                        2 -> {
                            val w = measure(text)
                            x = (avoid + pm.mlPx + pm.availWidth - w).coerceAtLeast(0f)
                            segs = null
                        }
                        3 -> {
                            // 显式左对齐: 缩进/左缩进后自然行,不两端对齐
                            val indentPx = ChapterComposer.paraIndentPx(para, typo)
                            val indented = ln.isParaStart && indentPx > 0f &&
                                !ChapterComposer.leadingIndented(cl.composed, ln.start)
                            x = avoid + pm.mlPx + if (indented) indentPx else 0f
                            segs = null
                        }
                        else -> {
                            // 0=跟随全局 / 4=显式两端对齐(无视全局开关)
                            val indentPx = ChapterComposer.paraIndentPx(para, typo)
                            val indented = ln.isParaStart && indentPx > 0f &&
                                !ChapterComposer.leadingIndented(cl.composed, ln.start)
                            x = avoid + pm.mlPx + if (indented) indentPx else 0f
                            val doJustify = align == 4 || typo.justify
                            val midPara = ln.kind == LineKind.BODY &&
                                ln.end < cl.composed.length && cl.composed[ln.end] != '\n'
                            segs = if (doJustify && midPara) {
                                PaginationEngine.justifySegments(text, pm.availWidth - (x - pm.mlPx), typo.fontPx, measure)
                                    ?.map { LineSeg(it.first, it.second) }
                            } else null
                        }
                    }
                    // 五期: 行内图片折算(段内偏移 → 行内下标;尺寸查 ChapterLines.inlineSizes)
                    val inlines = if (pi >= 0 && para!!.inlineImages.isNotEmpty()) {
                        val rngFirst = cl.paraRanges[pi].first
                        para.inlineImages.mapNotNull { im ->
                            val abs = rngFirst + im.start
                            if (abs in ln.start until ln.end) {
                                val sz = cl.inlineSizes[im.ref]
                                    ?: ImageSize(typo.fontPx.roundToInt(), typo.fontPx.roundToInt())
                                DrawInline(abs - ln.start, im.ref, sz.width, sz.height)
                            } else null
                        }
                    } else emptyList()
                    out += DrawLine(
                        text, x, (y + above + ln.ascentAbs).toFloat(), ln.kind == LineKind.TITLE,
                        global, segs, lineStyles(cl, ln, pi), inlineImages = inlines
                    )
                    linePara.add(pi)
                }
            }
            y += ln.pitch - (if (li == head) ln.paraAbove else 0)
        }
        // 批次四: 表格片段(表格整体顶 = 窗口首行顶 - 之前各行高累计;可负 = 从上页延续)
        for ((pi, win) in tableWindow) {
            val tl = cl.tableLayouts[pi] ?: continue
            val para = cl.paras.getOrNull(pi) ?: continue
            val pmT = paraMetrics(para, typo)
            var before = 0f
            for (r in 0 until win.first) before += tl.heights[r]
            val topY = (tableTopY[pi] ?: 0f) - before
            tables += DrawTable(tl, pmT.mlPx, topY, win.first, win.second)
        }
        // 七期: 盒组聚合(页内连续同 boxStyle 段落 → 一个矩形;跨页组边缘不画横向框)
        run {
            val order = paraTop.keys.sorted()
            // 已定位盒(嵌套子盒的定位基准)
            class PlacedBox(val fp: Int, val lp: Int, val style: BoxStyle, val left: Float, val right: Float)
            val placed = ArrayList<PlacedBox>()
            fun flushBox(fp: Int, lp: Int, style: BoxStyle) {
                val fpSafe = fp.coerceIn(0, cl.paras.size - 1)
                val lpSafe = lp.coerceIn(0, cl.paras.size - 1)
                val pm = paraMetrics(cl.paras[fpSafe], typo)
                val fontPx = typo.fontPx
                fun edge(i: Int): com.yukino.tool.module.reader.common.EdgeStyle? =
                    style.edges.getOrNull(i)?.takeIf { it.widthEm > 0f && it.style > 0 }
                val bt = edge(0)?.widthEm ?: 0f
                val br = edge(1)?.widthEm ?: 0f
                val bb = edge(2)?.widthEm ?: 0f
                val bl = edge(3)?.widthEm ?: 0f
                val tw = typo.textWidth.toFloat()
                // 批次四修复: 盒自身 width/margin 定位(段落继承的 ml/mr 含 padding 与
                // 居中偏移的复合语义,反推矩形会左右不对称)——盒有 width 时直接按盒定位。
                // 嵌套盒(段落区间含于已定位父盒)相对父盒内容区定位;margin auto 与
                // float:right/left 均在基准区内解析
                var baseL = 0f
                var baseR = tw
                val parent = placed.lastOrNull { p ->
                    p.fp < fp && p.lp > lp &&
                        cl.paraRanges[p.fp].first <= cl.paraRanges[fpSafe].first &&
                        cl.paraRanges[p.lp].last >= cl.paraRanges[lpSafe].last
                }
                if (parent != null) {
                    val ps = parent.style
                    baseL = parent.left + ps.padLeftEm * fontPx +
                        (ps.edges.getOrNull(3)?.takeIf { it.widthEm > 0f && it.style > 0 }?.widthEm ?: 0f) * fontPx
                    baseR = parent.right - ps.padRightEm * fontPx -
                        (ps.edges.getOrNull(1)?.takeIf { it.widthEm > 0f && it.style > 0 }?.widthEm ?: 0f) * fontPx
                }
                val baseW = baseR - baseL
                var left: Float
                var right: Float
                val boxW = style.widthCss?.px(fontPx, baseW) ?: tw
                val fSide = cl.paras[fpSafe].floatSide
                if (boxW in 1f..baseW) {
                    val mlB = style.marginLeft?.px(fontPx, baseW) ?: 0f
                    val mrB = style.marginRight?.px(fontPx, baseW) ?: 0f
                    when {
                        style.marginAuto == 1 -> { left = baseL + (baseW - boxW) / 2f + mlB; right = left + boxW }
                        style.marginAuto == 2 -> { right = baseR - mrB; left = right - boxW }
                        style.marginAuto == 3 -> { left = baseL + mlB; right = left + boxW }
                        fSide == 1 -> { right = baseR; left = right - boxW }   // float:right 贴右缘
                        fSide == 2 -> { left = baseL; right = left + boxW }
                        else -> { left = baseL + mlB; right = left + boxW }
                    }
                } else {
                    left = baseL + pm.mlPx
                    right = baseR - pm.mrPx
                }
                // CSS width = 内容宽 → 边框盒外扩 padding+border
                left = (left - style.padLeftEm * fontPx - bl * fontPx).coerceAtLeast(0f)
                right = (right + style.padRightEm * fontPx + br * fontPx).coerceAtMost(tw)
                placed += PlacedBox(fp, lp, style, left, right)
                val top = (paraTop[fp] ?: 0f) - style.padTopEm * fontPx - bt * fontPx
                // height 语义对齐浏览器: 固定高——盒不随内容撑高(内容超出画出盒外,
                // overflow visible),有 height 的盒因此保持声明形状(t-box1 11em 正方形=正圆)
                var bottom = style.heightCss?.px(fontPx, typo.textWidth.toFloat())?.let { hPx ->
                    top + hPx
                } ?: ((paraBottom[lp] ?: 0f) + style.padBottomEm * fontPx + bb * fontPx)
                val topOpen = fp > 0 && cl.paras[fp - 1].boxStyle == style
                val bottomOpen = lp < cl.paras.lastIndex && cl.paras[lp + 1].boxStyle == style
                if (right > left) {
                    val db = DrawBox(style, left, top, right, bottom, topOpen, bottomOpen)
                    boxes += db
                    boxRanges.add(fp to lp)
                    placed += PlacedBox(fp, lp, style, db.left, db.right)
                }
            }
            // 一遍收集连续同款盒段
            class Seg(var first: Int, var last: Int, val style: BoxStyle)
            val segs = ArrayList<Seg>()
            for (pi in order) {
                val style = cl.paras.getOrNull(pi)?.boxStyle ?: continue
                val lastSeg = segs.lastOrNull()
                if (lastSeg != null && lastSeg.style == style && lastSeg.last == pi - 1) lastSeg.last = pi
                else segs.add(Seg(pi, pi, style))
            }
            // 嵌套合并: 同款两段之间夹的段落全部带盒样式(全是子盒) → DOM 里本是同一个 div,
            // 被子盒打断的同一外盒合并为一个区间(否则外盒画成两截、子盒父查找失败飞位);
            // 中间隔着无盒段落 → 是两个独立盒,不合并
            var i = 0
            while (i < segs.size) {
                var mergedTo = -1
                for (k in i + 1 until segs.size) {
                    if (segs[k].style != segs[i].style) continue
                    var gap = false
                    for (p in segs[i].last + 1 until segs[k].first) {
                        if (cl.paras.getOrNull(p)?.boxStyle == null) { gap = true; break }
                    }
                    if (!gap) mergedTo = k
                    break
                }
                if (mergedTo >= 0) {
                    val mergedLast = segs[mergedTo].last
                    segs.removeAt(mergedTo)
                    segs[i].last = mergedLast
                } else i++
            }
            // 父盒先 flush(子盒的定位基准);同起点按终点降序(外层先)
            val flushOrder = segs.sortedWith(compareBy({ it.first }, { -it.last }))
            for (s in flushOrder) flushBox(s.first, s.last, s.style)
            // 盒内行 x 基准修正: 定宽盒(margin auto/float 在基准区内定位)的文字随盒走,
            // 对齐基准从版心换到盒——否则嵌套小标签场景文字与盒分离(t-box3 场景:
            // 盒贴父盒右缘而文字留在版心贴右位置)
            if (boxes.isNotEmpty()) {
                val fontPx = typo.fontPx
                val paraBox = HashMap<Int, DrawBox>()
                for ((bi, b) in boxes.withIndex()) {
                    val (bf, bl) = boxRanges[bi]
                    for (p in bf..bl) paraBox[p] = b   // 子盒后 flush 覆盖父盒
                }
                for ((idx, dl) in out.withIndex()) {
                    if (dl.text.isEmpty()) continue
                    val b = paraBox[linePara[idx]] ?: continue
                    val st = b.style
                    if (st.widthCss == null) continue
                    val para = cl.paras.getOrNull(linePara[idx]) ?: continue
                    val w = measure(dl.text)
                    dl.x = if (para.align == 1) {
                        // 居中段相对整盒居中(小圆标签 padding/border 属于圈的视觉部分)
                        b.left + (b.right - b.left - w) / 2f
                    } else {
                        val contentL = b.left + st.padLeftEm * fontPx +
                            (st.edges.getOrNull(3)?.takeIf { it.widthEm > 0f && it.style > 0 }?.widthEm ?: 0f) * fontPx
                        val mlPx = paraMetrics(para, typo).mlPx
                        contentL + (dl.x - mlPx)   // 保留行原对齐偏移,仅平移基准
                    }
                }
            }
        }
        return DrawLinesResult(out, boxes, tables)
    }

    // pos 所在段落下标(二分;段间换行位与空段不会被命中,返回 -1)
    internal fun paraIndexOf(cl: ChapterLines, pos: Int): Int {
        var lo = 0
        var hi = cl.paraRanges.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val r = cl.paraRanges[mid]
            when {
                pos < r.first -> hi = mid - 1
                pos > r.last -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    // 行内样式段折算: 行区间裁剪到所在段,段落 Run 映射为行内相对坐标。
    // 无 Run/无有效样式的行返回 null(单一样式快速路径,TXT 恒走此路径)
    internal fun lineStyles(cl: ChapterLines, ln: TextLine, paraIndex: Int): List<LineStyle>? {
        if (paraIndex < 0) return null
        val para = cl.paras[paraIndex]
        if (para.runs.isEmpty()) return null
        val rng = cl.paraRanges[paraIndex]
        val a = maxOf(ln.start, rng.first)
        val b = minOf(ln.end, rng.last + 1)
        if (a >= b) return null
        val off = a - rng.first
        val out = ArrayList<LineStyle>()
        for (run in para.runs) {
            val s = maxOf(run.start, off)
            val e = minOf(run.end, off + (b - a))
            if (s < e) out += LineStyle(
                s - off, e - off, run.style,
                run.sizeEm, run.color, run.shadow, run.fontId?.let { cl.fontIds[it] }
            )
        }
        if (out.isEmpty() || out.all {
                it.style == 0 && it.sizeEm == null && it.color == null && !it.shadow && it.font == null
            }
        ) return null
        return out
    }

    // 章 → 行模型(带缓存)。版式或书(内容源)变化整体失效——缓存按章下标索引,
    // 换书不清会串书
    private fun chapterLines(
        content: BookContent,
        chapterIndex: Int,
        typo: ResolvedTypography
    ): ChapterLines = synchronized(this) {
        resetCacheIfStale(content, typo)
        lineCache.getOrPut(chapterIndex) {
            buildChapterLines(content, chapterIndex, typo)
        }
    }

    // buildSpecs 的并行断行结果入缓存: 首次物化即命中,不重复断行
    private fun putLines(content: BookContent, chapterIndex: Int, cl: ChapterLines, typo: ResolvedTypography): ChapterLines {
        synchronized(this) {
            resetCacheIfStale(content, typo)
            lineCache[chapterIndex] = cl
        }
        return cl
    }

    private fun resetCacheIfStale(content: BookContent, typo: ResolvedTypography) {
        if (cacheTypo != typo || cacheContent !== content) {   // data class 相等: 版式变化整体失效
            lineCache.clear()
            cacheTypo = typo
            cacheContent = content
        }
    }

    // 页首全书偏移 → 全局页号: 二分找最后一个页首偏移 <= 目标的页
    fun locatePage(specs: List<PageSpec>, globalCharOffset: Long): Int {
        var lo = 0
        var hi = specs.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (specs[mid].globalCharOffset <= globalCharOffset) lo = mid else hi = mid - 1
        }
        return lo
    }

    // 页首全书偏移 → 百分比
    fun percentOf(content: BookContent, spec: PageSpec): Double = when (spec.kind) {
        PageKind.COVER -> 0.0
        PageKind.BACK -> 1.0
        PageKind.CONTENT -> (spec.globalCharOffset / content.totalChars.toDouble()).coerceIn(0.0, 1.0)
    }
}
