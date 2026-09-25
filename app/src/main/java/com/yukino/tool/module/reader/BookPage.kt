package com.yukino.tool.module.reader

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
// 绘制由行类别决定字号/加粗,不再依赖 span
class ChapterLines(
    val composed: CharSequence,   // 章节名 + "\n\n" + 正文
    val bodyStart: Int,           // 正文区起点(之前是标题块)
    val bodyZero: Long,           // 正文零点的全书偏移(章起点 + 剥掉的标题字符数)
    val lines: List<TextLine>
)

// 可绘制的行(物化产物,坐标相对版心左上角)。基线布局在物化时算好,绘制层只做平移与 drawText
class DrawLine(
    val text: String,
    val x: Float,                 // 行首 x: 段首行为首行缩进,其余 0(自带缩进的段落缩进在字符里)
    val baseline: Float,
    val title: Boolean,           // true 用标题 paint(大字号加粗)
    val lineStartGlobal: Long,    // 行首字符的全书偏移(行内第 i 字符 = lineStartGlobal + i,正文区线性)
    val segments: List<LineSeg>? = null   // 两端对齐拉伸分段(相对行首 x);null = 自然宽整行画
)

class LineSeg(val text: String, val x: Float)

// 可渲染页: 自包含(行布局+页眉页脚文案),渲染层不接触章/行模型。
// 不可变——拖拽预览与落账引用同一实例,"看到的页"=="翻到的页"
class BookPage(
    val spec: PageSpec,
    val headerTitle: String,      // 空串不画
    val footerLabel: String,      // 空串不画
    val lines: List<DrawLine> = emptyList(),   // 正文页的行(相对版心顶的基线坐标)
    val virtualLayout: StaticLayout? = null    // 封面/封底: 居中布局,与 lines 二选一
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
// 样式 span(标题字号/加粗/首行缩进)只供断行度量,绘制由行类别决定
object ChapterComposer {
    const val TITLE_SCALE = 1.4f

    // 正文在合成文本中的起点: title + "\n\n"
    fun bodyStart(titleLength: Int): Int = titleLength + 2

    fun compose(title: String, body: String, typo: ResolvedTypography): Spanned {
        val sb = SpannableStringBuilder(title).append("\n\n").append(body)
        sb.setSpan(RelativeSizeSpan(TITLE_SCALE), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        applyIndent(sb, bodyStart(title.length), sb.length, typo.indentPx)
        return sb
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

// 分页器: 全书文本+版式 → 全量页目录;页目录+章行模型(带缓存) → 可渲染页。
//
// 网格排版: 断行(StaticLayout,含系统禁则)与行布局(行模型)分离——
// 整章只断行一次,行高/段前距量化到网格,页高与版心高的零头天然小于一个网格,
// 页底视觉铺满;行高与段前距全章恒定,相邻页的行位天然对齐,匀齐分摊不再需要
object BookPager {

    // 章行模型缓存: 物化同章页(翻页/邻页预物化)免重复断行。版式变化整体失效,
    // 小 LRU 控内存。物化并发(Default 线程)经 synchronized 串行化,首建后命中
    private var cacheTypo: ResolvedTypography? = null
    private val lineCache = object : LinkedHashMap<Int, ChapterLines>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterLines>) = size > 4
    }

    // 全书页目录: 封面 + 各章 + 封底 的扁平页序列。
    // 各章断行互相独立(每次调用自建 Paint/StaticLayout,无共享可变状态),按章真并行执行。
    // 断行是大计算,必须在 Default 调度器上——留在主线程(调用方 LaunchedEffect 的调度器)
    // 时,连续改版式触发的重排会积压输入事件导致 ANR。
    // 信号量限流控制同时驻留内存的章断行布局数;awaitAll 按发起顺序取结果,与串行结果一致
    suspend fun buildSpecs(book: ReaderBook, fullText: String, typo: ResolvedTypography): List<PageSpec> =
        coroutineScope {
            val specs = ArrayList<PageSpec>()
            specs += PageSpec(PageKind.COVER, -1, 0, 1, 0L, "")
            if (book.chapters.isEmpty()) {
                // 无章节书: 整本为单章,书名作章名
                val cl = withContext(Dispatchers.Default) {
                    buildChapterLines(book, fullText, typo, book.title, 0L, 0)
                }
                specs += specsOf(putLines(0, cl, typo), 0, 0L, book.title, typo)
            } else {
                val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val permits = Semaphore(parallelism)
                val perChapter = book.chapters.indices.map { c ->
                    async(Dispatchers.Default) {
                        permits.withPermit {
                            ensureActive()
                            val ch = book.chapters[c]
                            val cl = putLines(
                                c, buildChapterLines(book, fullText, typo, ch.title, ch.startChar, c), typo
                            )
                            specsOf(cl, c, ch.startChar, ch.title, typo)
                        }
                    }
                }
                perChapter.awaitAll().forEach { specs += it }   // 发起顺序 = 章序,结果确定性不变
            }
            specs += PageSpec(PageKind.BACK, book.chapters.size, 0, 1, book.totalChars, "")
            specs
        }

    // 章 → 行模型。断行度量来自固定字体度量,与传入 layout 的行序结合生成行高。
    // 仅在此处构建 StaticLayout,断行结果入缓存后 layout 即弃
    private fun buildChapterLines(
        book: ReaderBook,
        fullText: String,
        typo: ResolvedTypography,
        title: String,
        chapterStartGlobal: Long,
        chapterIndex: Int
    ): ChapterLines {
        val (body, stripped) = ChapterComposer.stripLeadingTitle(
            chapterText(fullText, book, chapterIndex), title
        )
        val composed = ChapterComposer.compose(title, body, typo)
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
        // 全章行高一致,跨页行位对齐保持)。标题行/空行不参与放大
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
        return ChapterLines(composed, bodyStart, chapterStartGlobal + stripped, lines)
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
        book: ReaderBook,
        fullText: String,
        spec: PageSpec,
        typo: ResolvedTypography
    ): BookPage {
        if (spec.kind != PageKind.CONTENT) {
            val text = if (spec.kind == PageKind.COVER) book.title else "最后一页了"
            return BookPage(spec, "", "", virtualLayout = Typography.buildVirtualLayout(text, typo))
        }
        val chapter = book.chapters.getOrNull(spec.chapterIndex)   // 无章节书: 单章约定
        val cl = chapterLines(
            book, fullText, typo, spec.chapterIndex,
            chapter?.title ?: book.title.takeIf { book.chapters.isEmpty() } ?: "",
            chapter?.startChar ?: 0L
        )
        val windows = paginate(cl, typo)
        val slice = windows[spec.chapterPageIndex.coerceIn(0, windows.lastIndex)]
        val percent = percentOf(book, spec)
        val label = "${spec.chapterPageIndex + 1}/${spec.chapterPageCount} ${(percent * 100).roundToInt()}%"
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = typo.fontPx }
        val chapterStartGlobal = chapter?.startChar ?: 0L
        return BookPage(
            spec, spec.chapterTitle, label,
            drawLines(cl, slice, typo, measure = { paint.measureText(it) }, chapterStartGlobal = chapterStartGlobal)
        )
    }

    // 行窗口 → 可绘制行。跳过空行(其行高已占位);窗口首个非空行豁免段前距(与分页同口径);
    // 段中行做两端对齐拉伸(行尾之后不是换行/章末即为段中行;标题行/段落末行保持自然参差)。
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
                    globalOffset(ln.start, cl.bodyStart, cl.bodyZero, chapterStartGlobal), segs
                )
            }
            y += ln.pitch - (if (li == head) ln.paraAbove else 0)
        }
        return out
    }

    // 章 → 行模型(带缓存)。title/chapterStart 由调用方给出(无章节书的单章约定)
    private fun chapterLines(
        book: ReaderBook,
        fullText: String,
        typo: ResolvedTypography,
        chapterIndex: Int,
        title: String,
        chapterStartGlobal: Long
    ): ChapterLines = synchronized(this) {
        if (cacheTypo != typo) {   // data class 相等: 版式变化整体失效
            lineCache.clear()
            cacheTypo = typo
        }
        lineCache.getOrPut(chapterIndex) {
            buildChapterLines(book, fullText, typo, title, chapterStartGlobal, chapterIndex)
        }
    }

    // buildSpecs 的并行断行结果入缓存: 首次物化即命中,不重复断行
    private fun putLines(chapterIndex: Int, cl: ChapterLines, typo: ResolvedTypography): ChapterLines {
        synchronized(this) {
            if (cacheTypo != typo) {
                lineCache.clear()
                cacheTypo = typo
            }
            lineCache[chapterIndex] = cl
        }
        return cl
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
    fun percentOf(book: ReaderBook, spec: PageSpec): Double = when (spec.kind) {
        PageKind.COVER -> 0.0
        PageKind.BACK -> 1.0
        PageKind.CONTENT -> (spec.globalCharOffset / book.totalChars.toDouble()).coerceIn(0.0, 1.0)
    }
}

// 章节正文(全书文本按章表区间裁切;无章节的书整本为单章)
internal fun chapterText(fullText: String?, book: ReaderBook, chapterIndex: Int): String {
    if (fullText == null) return ""
    val chapters = book.chapters
    if (chapters.isEmpty()) return fullText
    val idx = chapterIndex.coerceIn(0, chapters.lastIndex)
    val start = chapters[idx].startChar.toInt().coerceIn(0, fullText.length)
    val end = if (idx + 1 < chapters.size) {
        chapters[idx + 1].startChar.toInt().coerceIn(start, fullText.length)
    } else fullText.length
    return fullText.substring(start, end)
}
