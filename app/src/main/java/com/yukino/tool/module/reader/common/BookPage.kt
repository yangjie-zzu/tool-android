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
    val fontFiles: Map<String, String> = emptyMap() // family → 字体文件绝对路径
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
    val x: Float,                 // 行首 x: 段首行为首行缩进,其余 0(自带缩进的段落缩进在字符里)
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

class LineSeg(val text: String, val x: Float)

// 可渲染页: 自包含(行布局+页眉页脚文案),渲染层不接触章/行模型。
// 不可变——拖拽预览与落账引用同一实例,"看到的页"=="翻到的页"
class BookPage(
    val spec: PageSpec,
    val headerTitle: String,      // 空串不画
    val footerLabel: String,      // 空串不画
    val lines: List<DrawLine> = emptyList(),   // 正文页的行(相对版心顶的基线坐标)
    val boxes: List<DrawBox> = emptyList(),    // 七期: 盒组矩形(画在文字下层)
    val fontFiles: Map<String, String> = emptyMap(),  // 七期批次三: family → 字体文件路径(绘制层加载 Typeface)
    val virtualLayout: StaticLayout? = null,   // 封面/封底: 居中布局,与 lines 二选一
    val coverImage: String? = null,            // 封面页: 封面图路径(与 virtualLayout 二选一,优先图)
    val coverWidth: Int = 0,                   // 封面显示尺寸(物化时按版心宽等比换算;0 = 未就绪)
    val coverHeight: Int = 0
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
    private fun applyRunSpan(sb: SpannableStringBuilder, s: Int, e: Int, style: Int, sizeEm: Float? = null) {
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
                        ensureActive()
                        val cl = putLines(
                            content, c, buildChapterLines(content, c, typo), typo
                        )
                        specsOf(cl, c, content.chapterStart(c), content.chapterTitle(c), typo)
                    }
                }
            }
            perChapter.awaitAll().forEach { specs += it }   // 发起顺序 = 章序,结果确定性不变
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
                    if (prevBelowPx != 0f) extra += prevBelowPx
                    para.spaceAboveEm?.let { extra += it.px(typo.fontPx, tw) }
                }
                extraAbove[i] = extra
                prevBelowPx = if (typo.bookSpacing) (para.spaceBelowEm?.px(typo.fontPx, tw) ?: 0f) else 0f
                p += para.text.length + 1
            }
        }

        // 七期: 段级左右度量(整段缩进/定宽)与右缩进段独立断行。
        // 右缩进(margin-right 或定宽推出的右留白)使主 layout 全宽断行过宽,
        // 这些段用独立 StaticLayout(宽 = 可用宽)断行,行区间替换主行
        fun paraMetricsOf(p: Paragraph?): ParaMetrics = paraMetrics(p, typo)
        val overrides = HashMap<Int, List<IntRange>>()
        run {
            for ((pi, para) in paras.withIndex()) {
                if (para.isImage) continue   // 图片段单行,不参与独立断行(对齐由物化层处理)
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

        // BODY 行的段级行距与基线: 无书内行距用全局贴合行高;有则行框 = 字号×倍率(CSS line-height 语义,
        // 覆盖全局行距),行框空白(可负,如 0% 压行)上下对分
        fun paraLinePitch(para: Paragraph?): Pair<Int, Int> {
            val lh = para?.lineSpacingMult ?: return bodyPitch to (bodyAscentAbs + bodyShift)
            val frame = typo.fontPx * lh
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
                        val bookGrid = if (isStart) {
                            PaginationEngine.gridCeil(extraAbove[curPi], Typography.GRID_PX).coerceAtLeast(-paraAbove)
                        } else 0
                        val (bp, ba) = paraLinePitch(para)
                        val above = if (isStart) paraAbove + bookGrid else 0
                        lines += TextLine(r.first, r.last + 1, LineKind.BODY, isStart, above + bp, above, ba)
                    }
                }
                continue
            }
            val bookExtra = if (isParaStart && rangeCursor < extraAbove.size) extraAbove[rangeCursor] else 0f
            // 六期 A2: 负 margin 最多抵消全局段前距(不侵蚀行高本体,防文字重叠)
            val bookGrid = PaginationEngine.gridCeil(bookExtra, Typography.GRID_PX)
                .coerceAtLeast(-paraAbove)
            val curPara = if (curPi >= 0 && curPi < paras.size) paras[curPi] else null
            val (pitch, ascentAbs) = when (kind) {
                LineKind.BLANK -> LineGrid.blankPitch(Typography.GRID_PX) to bodyAscentAbs
                LineKind.TITLE -> titlePitch to (titleAscentAbs + titleShift)
                LineKind.BODY -> {
                    val (bp, ba) = paraLinePitch(curPara)
                    val above = if (isParaStart) {
                        paraAbove + bookGrid
                    } else 0
                    above + bp to ba
                }
            }
            lines += TextLine(s, e, kind, isParaStart, pitch, if (isParaStart) paraAbove + bookGrid else 0, ascentAbs)
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
            paras, paraRanges, imageSizes, inlineSizes, overrides, doc.fontIds, doc.fontFiles
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
            if (p.widthCenter) {
                ml = (tw - w) / 2f
                mr = tw - ml - w
            } else {
                mr = (tw - ml - w).coerceAtLeast(0f)
            }
        }
        ml = ml.coerceIn(0f, tw * 0.45f)   // 防病态: 缩进不吞没行
        mr = mr.coerceIn(0f, tw * 0.45f)
        return ParaMetrics(ml, mr, tw - ml - mr, mr > 0.5f)
    }

    // 右缩进/定宽段的独立断行 layout: 宽 = 可用宽,首行缩进由 leading margin 承担,
    // 对齐跟随段落(居中/右对齐在可用宽内)。与主 layout 同一 paint/break strategy,断行确定性一致
    private fun overrideLayout(para: Paragraph, pm: ParaMetrics, typo: ResolvedTypography): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = typo.fontPx }
        val csb = SpannableStringBuilder(para.text)
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

    // 章 → 页窗口: 网格化行高切页(页首首个非空行豁免段前距)+ 裁掉尾部空白页
    private fun paginate(cl: ChapterLines, typo: ResolvedTypography): List<PageSlice> =
        PaginationEngine.trimTrailingBlank(
            PaginationEngine.splitPages(cl.lines, typo.textHeight)
        ) { w -> (w.startLine until w.endLineExclusive).all { cl.lines[it].kind == LineKind.BLANK } }

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
    fun materialize(
        content: BookContent,
        spec: PageSpec,
        typo: ResolvedTypography
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
        val cl = chapterLines(content, idx, typo)
        val windows = paginate(cl, typo)
        val slice = windows[spec.chapterPageIndex.coerceIn(0, windows.lastIndex)]
        val percent = percentOf(content, spec)
        val label = "${spec.chapterPageIndex + 1}/${spec.chapterPageCount} ${(percent * 100).roundToInt()}%"
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = typo.fontPx }
        val chapterStartGlobal = content.chapterStart(idx)
        val drawn = drawLinesWithBoxes(cl, slice, typo, measure = { paint.measureText(it) }, chapterStartGlobal = chapterStartGlobal)
        return BookPage(
            spec, spec.chapterTitle, label,
            drawn.lines, drawn.boxes, cl.fontFiles
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

    internal class DrawLinesResult(val lines: List<DrawLine>, val boxes: List<DrawBox>)

    internal fun drawLinesWithBoxes(
        cl: ChapterLines,
        slice: PageSlice,
        typo: ResolvedTypography,
        measure: (String) -> Float,
        chapterStartGlobal: Long = 0L
    ): DrawLinesResult {
        val out = ArrayList<DrawLine>(slice.endLineExclusive - slice.startLine)
        val boxes = ArrayList<DrawBox>()
        var head = slice.startLine
        while (head < slice.endLineExclusive && cl.lines[head].kind == LineKind.BLANK) head++
        // 段落行块区间(页内): 盒组聚合用
        val paraTop = HashMap<Int, Float>()
        val paraBottom = HashMap<Int, Float>()
        var y = 0
        for (li in slice.startLine until slice.endLineExclusive) {
            val ln = cl.lines[li]
            val above = if (li == head) 0 else ln.paraAbove
            if (ln.kind != LineKind.BLANK) {
                val global = globalOffset(ln.start, cl.bodyStart, cl.bodyZero, chapterStartGlobal)
                val pi = paraIndexOf(cl, ln.start)
                val para = if (pi >= 0) cl.paras[pi] else null
                val pm = paraMetrics(para, typo)
                paraTop[pi] = minOf(paraTop[pi] ?: Float.MAX_VALUE, (y + above).toFloat())
                paraBottom[pi] = maxOf(paraBottom[pi] ?: 0f, (y + ln.pitch).toFloat())
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
                } else {
                    val text = cl.composed.substring(ln.start, ln.end)
                    val align = para?.align ?: 0
                    val x: Float
                    val segs: List<LineSeg>?
                    when (align) {
                        1 -> {
                            val w = measure(text)
                            x = (pm.mlPx + (pm.availWidth - w) / 2f).coerceAtLeast(0f)
                            segs = null
                        }
                        2 -> {
                            val w = measure(text)
                            x = (pm.mlPx + pm.availWidth - w).coerceAtLeast(0f)
                            segs = null
                        }
                        3 -> {
                            // 显式左对齐: 缩进/左缩进后自然行,不两端对齐
                            val indentPx = ChapterComposer.paraIndentPx(para, typo)
                            val indented = ln.isParaStart && indentPx > 0f &&
                                !ChapterComposer.leadingIndented(cl.composed, ln.start)
                            x = pm.mlPx + if (indented) indentPx else 0f
                            segs = null
                        }
                        else -> {
                            // 0=跟随全局 / 4=显式两端对齐(无视全局开关)
                            val indentPx = ChapterComposer.paraIndentPx(para, typo)
                            val indented = ln.isParaStart && indentPx > 0f &&
                                !ChapterComposer.leadingIndented(cl.composed, ln.start)
                            x = pm.mlPx + if (indented) indentPx else 0f
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
                }
            }
            y += ln.pitch - (if (li == head) ln.paraAbove else 0)
        }
        // 七期: 盒组聚合(页内连续同 boxStyle 段落 → 一个矩形;跨页组边缘不画横向框)
        run {
            val order = paraTop.keys.sorted()
            var accFirst = -1
            var accLast = -1
            var accStyle: BoxStyle? = null
            fun flushBox() {
                val style = accStyle ?: return
                val fp = accFirst
                val lp = accLast
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
                val left = (pm.mlPx - style.padLeftEm * fontPx - bl * fontPx).coerceAtLeast(0f)
                val right = (typo.textWidth - pm.mrPx - style.padRightEm * fontPx - br * fontPx)
                    .coerceAtMost(typo.textWidth.toFloat())
                val top = (paraTop[fp] ?: 0f) - style.padTopEm * fontPx - bt * fontPx
                val bottom = (paraBottom[lp] ?: 0f) + style.padBottomEm * fontPx + bb * fontPx
                val topOpen = fp > 0 && cl.paras[fp - 1].boxStyle == style
                val bottomOpen = lp < cl.paras.lastIndex && cl.paras[lp + 1].boxStyle == style
                if (right > left) boxes += DrawBox(style, left, top, right, bottom, topOpen, bottomOpen)
            }
            for (pi in order) {
                val style = cl.paras.getOrNull(pi)?.boxStyle
                if (style != null && style == accStyle) {
                    accLast = pi
                } else {
                    flushBox()
                    accStyle = style
                    accFirst = pi
                    accLast = pi
                }
            }
            flushBox()
        }
        return DrawLinesResult(out, boxes)
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
