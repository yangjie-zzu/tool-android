package com.yukino.tool.module.reader.common
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
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
    val imageDrawSizes: Map<Int, ImageSize> = emptyMap() // 图片段显示尺寸(px,paraIndex → 尺寸)
)

// 图片行的显示尺寸(版心坐标系;按版心宽等比缩放,超高图缩到一页内)
class ImageSize(val width: Int, val height: Int)

// 行内样式段(相对行文本坐标)。绘制层按段切 paint;与两端对齐拉伸分段正交组合
class LineStyle(val start: Int, val end: Int, val style: Int)

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
    val imageHeight: Float = 0f
)

class LineSeg(val text: String, val x: Float)

// 可渲染页: 自包含(行布局+页眉页脚文案),渲染层不接触章/行模型。
// 不可变——拖拽预览与落账引用同一实例,"看到的页"=="翻到的页"
class BookPage(
    val spec: PageSpec,
    val headerTitle: String,      // 空串不画
    val footerLabel: String,      // 空串不画
    val lines: List<DrawLine> = emptyList(),   // 正文页的行(相对版心顶的基线坐标)
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
    // 投影文本与一期 compose(title, body) 逐字符一致(段落 = joinToString("\n") 的切分)
    fun compose(title: String, paras: List<Paragraph>, typo: ResolvedTypography): Spanned {
        val sb = SpannableStringBuilder(title).append("\n\n")
        var pos = title.length + 2
        paras.forEachIndexed { i, p ->
            if (i > 0) { sb.append('\n'); pos++ }
            sb.append(p.text)
            for (run in p.runs) {
                if (run.end > run.start) applyRunSpan(sb, pos + run.start, pos + run.end, run.style)
            }
            pos += p.text.length
        }
        sb.setSpan(RelativeSizeSpan(TITLE_SCALE), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        applyIndent(sb, bodyStart(title.length), sb.length, typo.indentPx)
        return sb
    }

    // 一期纯投影入口保留(TXT 路径/既有测试使用;与段落版投影逐字符一致)
    fun compose(title: String, body: String, typo: ResolvedTypography): Spanned =
        compose(title, body.split('\n').map { Paragraph(it) }, typo)

    // Run 样式位 → 字符样式 span(度量用;基线偏移类由绘制层处理)
    private fun applyRunSpan(sb: SpannableStringBuilder, s: Int, e: Int, style: Int) {
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
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

    // 首行缩进 span: 只给 [from, to) 内的非空白段;
    // 段首已带全角/半角空格缩进的段落视为自带缩进,不再叠加(避免双重缩进)。
    // 源文本的空格字符原样保留(不能删,删了会破坏字符偏移映射)
    private fun applyIndent(sb: SpannableStringBuilder, from: Int, to: Int, indentPx: Float) {
        if (indentPx <= 0f) return
        var paraStart = from
        var i = from
        while (i <= to) {
            if (i == to || sb[i] == '\n') {
                if ((paraStart until i).any { !sb[it].isWhitespace() } && !leadingIndented(sb, paraStart)) {
                    sb.setSpan(
                        LeadingMarginSpan.Standard(indentPx.toInt(), 0),
                        paraStart, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                paraStart = i + 1
            }
            i++
        }
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
        val composed = ChapterComposer.compose(title, paras, typo)
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
        val lines = ArrayList<TextLine>(measure.lineCount)
        for (i in 0 until measure.lineCount) {
            var s = measure.getLineStart(i)
            var e = measure.getLineEnd(i)
            if (e > s && composed[e - 1] == '\n') e--
            val kind = lineKind(composed, s, e, bodyStart)
            val isParaStart = kind == LineKind.BODY && paraStart(composed, s, bodyStart)
            val (pitch, ascentAbs) = when (kind) {
                LineKind.BLANK -> LineGrid.blankPitch(Typography.GRID_PX) to bodyAscentAbs
                LineKind.TITLE -> titlePitch to (titleAscentAbs + titleShift)
                LineKind.BODY -> {
                    val above = if (isParaStart) paraAbove else 0
                    above + bodyPitch to (bodyAscentAbs + bodyShift)
                }
            }
            lines += TextLine(s, e, kind, isParaStart, pitch, if (isParaStart) paraAbove else 0, ascentAbs)
        }

        // 段落区间表(正文区,不含段间换行;与 paras 对齐,空段为空区间)
        val paraRanges = ArrayList<IntRange>(paras.size)
        run {
            var p = bodyStart
            for (para in paras) {
                paraRanges += p until (p + para.text.length)
                p += para.text.length + 1
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
            val size = if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
                ImageSize(typo.textWidth, typo.fontPx.toInt() * 3)   // 坏图占位 3 行高,可见可感知
            } else {
                val w = typo.textWidth
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
            val imgPitch = old.paraAbove + PaginationEngine.gridCeil(size.height.toFloat(), Typography.GRID_PX)
            lines[li] = TextLine(old.start, old.end, old.kind, old.isParaStart, imgPitch, old.paraAbove, 0)
        }

        return ChapterLines(
            composed, bodyStart, content.chapterStart(chapterIndex) + stripped, lines,
            paras, paraRanges, imageSizes
        )
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
        return BookPage(
            spec, spec.chapterTitle, label,
            drawLines(cl, slice, typo, measure = { paint.measureText(it) }, chapterStartGlobal = chapterStartGlobal)
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
    ): List<DrawLine> {
        val out = ArrayList<DrawLine>(slice.endLineExclusive - slice.startLine)
        var head = slice.startLine
        while (head < slice.endLineExclusive && cl.lines[head].kind == LineKind.BLANK) head++
        var y = 0
        for (li in slice.startLine until slice.endLineExclusive) {
            val ln = cl.lines[li]
            val above = if (li == head) 0 else ln.paraAbove
            if (ln.kind != LineKind.BLANK) {
                val global = globalOffset(ln.start, cl.bodyStart, cl.bodyZero, chapterStartGlobal)
                val pi = paraIndexOf(cl, ln.start)
                val para = if (pi >= 0) cl.paras[pi] else null
                if (para?.isImage == true) {
                    val sz = cl.imageDrawSizes[pi] ?: ImageSize(0, 0)
                    out += DrawLine(
                        "", 0f, (y + above).toFloat(), false, global,
                        imageRef = para.imageRef,
                        imageWidth = sz.width.toFloat(),
                        imageHeight = sz.height.toFloat()
                    )
                } else {
                    val text = cl.composed.substring(ln.start, ln.end)
                    val indented = ln.isParaStart && typo.indentPx > 0f &&
                        !ChapterComposer.leadingIndented(cl.composed, ln.start)
                    val x = if (indented) typo.indentPx else 0f
                    val midPara = ln.kind == LineKind.BODY &&
                        ln.end < cl.composed.length && cl.composed[ln.end] != '\n'
                    val segs = if (typo.justify && midPara) {
                        PaginationEngine.justifySegments(text, typo.textWidth - x, typo.fontPx, measure)
                            ?.map { LineSeg(it.first, it.second) }
                    } else null
                    out += DrawLine(
                        text, x, (y + above + ln.ascentAbs).toFloat(), ln.kind == LineKind.TITLE,
                        global, segs, lineStyles(cl, ln, pi)
                    )
                }
            }
            y += ln.pitch - (if (li == head) ln.paraAbove else 0)
        }
        return out
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
            if (s < e) out += LineStyle(s - off, e - off, run.style)
        }
        if (out.isEmpty() || out.all { it.style == 0 }) return null
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
