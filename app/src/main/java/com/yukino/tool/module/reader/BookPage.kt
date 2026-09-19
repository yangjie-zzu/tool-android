package com.yukino.tool.module.reader

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

// 页末行手工两端对齐的绘制数据: layout 里该行仍是系统默认参差绘制,
// 渲染层先用背景色盖掉它,再按 segments(片段+相对行首 x)等宽拉伸重画
class LastLineDraw(
    val segments: List<LineSeg>,
    val topPx: Float,        // 相对 layout 原点
    val baselinePx: Float,
    val bottomPx: Float
)

class LineSeg(val text: String, val x: Float)

// 可渲染页: 自包含(自身布局+页眉页脚文案),渲染层不接触章节/行切片概念。
// 不可变——拖拽预览与落账引用同一实例,"看到的页"=="翻到的页"
class BookPage(
    val spec: PageSpec,
    val layout: StaticLayout,
    val headerTitle: String,      // 空串不画
    val footerLabel: String,      // 空串不画
    val topOffsetPx: Float = 0f,  // 页首额外下移: 底部剩余空白分配到顶部的一份(垂直匀齐)
    val lastLine: LastLineDraw? = null,
    val clipBottomPx: Float = Float.MAX_VALUE   // 可见内容底(layout 相对坐标): 裁掉截断补偿的上下文行
)

// 章节文本合成: 章节名(1.4倍加粗)+空行+正文,样式随 span 进入文本流。
// 标题任意长自然换行不截断;首行缩进只作用于正文段落(标题段/空行不缩进)
object ChapterComposer {
    const val TITLE_SCALE = 1.4f

    // 正文在合成文本中的起点: title + "\n\n"
    fun bodyStart(titleLength: Int): Int = titleLength + 2

    fun compose(title: String, body: String, typo: ResolvedTypography): Spanned {
        val sb = SpannableStringBuilder(title).append("\n\n").append(body)
        sb.setSpan(RelativeSizeSpan(TITLE_SCALE), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        applyIndent(sb, bodyStart(title.length), sb.length, typo.indentPx)
        applyParagraphSpacing(sb, bodyStart(title.length), sb.length, typo, { q -> q == bodyStart(title.length) || sb[q - 1] == '\n' })
        return sb
    }
    // 段距 span: 段落首行上方加 paraExtra 空隙(段前距,对空行/单换行两种分格式一致);
    // 空行压缩 span: 空行行高压到最小。chooseHeight 对同一行会多次调用,必须绝对值赋值
    private class ParaStartSpan(
        private val normalAscent: Int,
        private val extraPx: Int
    ) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int,
            spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt
        ) {
            fm.ascent = normalAscent - extraPx
        }
    }

    private class BlankLineSpan(
        private val lineExtraPx: Int   // 行距增量: StaticLayout 会把它加到行 descent 上,需预抵消
    ) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int,
            spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt
        ) {
            // 空行压到 ~2px: descent 预扣行距增量(add 在 chooseHeight 之后追加),
            // 空行无字形,负 descent 无副作用
            fm.ascent = -1
            fm.descent = 1 - lineExtraPx
        }
    }

    // 扫描 [from, to): 空行压缩 + 段首行上方加段距。
    // isParaStart(q) = sb 内下标 q 是否段落首字符(由调用方结合上文判定)
    // chapterHasBlank: 整章级"含空行"判定。段距扣减量依赖它,测量(整章)与单页渲染必须同值,
    // 否则无空行的页每段用全额段前距、行高比测量时更高,页尾行会被裁掉且在后续页不补
    internal fun applyParagraphSpacing(
        sb: SpannableStringBuilder, from: Int, to: Int,
        typo: ResolvedTypography, isParaStart: (Int) -> Boolean,
        chapterHasBlank: Boolean? = null
    ): Int {
        if (from >= to) return 0
        val fmf = Paint.FontMetrics()
        TextPaint().apply { textSize = typo.fontPx }.getFontMetrics(fmf)

        // 空行压缩
        var hasBlank = false
        var ls = from
        while (ls < to) {
            var le = ls
            while (le < to && sb[le] != '\n') le++
            if (le < to && (ls until le).all { sb[it].isWhitespace() }) {
                hasBlank = true
                sb.setSpan(
                    BlankLineSpan(typo.lineExtraPx.toInt()),
                    ls, (le + 1).coerceAtMost(to), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            ls = le + 1
        }
        if (chapterHasBlank != null) hasBlank = chapterHasBlank

        // 段前距: 空行书扣除空行压缩后的残留高度,两种分格式观感一致
        val extra = resolveParaExtra(typo, hasBlank)
        if (extra <= 0) return 0
        var paraCount = 0
        ls = from
        while (ls < to) {
            var le = ls
            while (le < to && sb[le] != '\n') le++
            val blank = (ls until le).all { sb[it].isWhitespace() }
            if (!blank && isParaStart(ls)) {
                paraCount++
                sb.setSpan(
                    ParaStartSpan(fmf.ascent.toInt(), extra),
                    ls, (ls + 1).coerceAtMost(to), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            ls = le + 1
        }
        return paraCount
    }

    // 整章正文是否含空行(章级判定)。段距扣减量依赖它,测量(整章)与单页物化必须传同一值
    fun hasBlankLine(text: CharSequence, from: Int, to: Int): Boolean {
        var ls = from
        while (ls < to) {
            var le = ls
            while (le < to && text[le] != '\n') le++
            if (le < to && (ls until le).all { text[it].isWhitespace() }) return true
            ls = le + 1
        }
        return false
    }

    // 段前距: 章内含空行时,空行已被压缩,段距扣除一行行距的残留,否则全额
    internal fun resolveParaExtra(typo: ResolvedTypography, hasBlank: Boolean): Int =
        if (hasBlank) (typo.paraExtraPx - typo.lineExtraPx).roundToInt().coerceAtLeast(0)
        else typo.paraExtraPx.toInt()

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

// 分页器: 全书文本+版式 → 全量页目录(章测量 layout 用完即弃);页目录 → 可渲染页
object BookPager {

    private const val DBG = true   // 页末行对齐诊断日志(ReaderJustify),定位问题用

    // 全书页目录: 封面 + 各章 + 封底 的扁平页序列。
    // 各章测量互相独立(每次调用自建 Paint/StaticLayout,无共享可变状态),按章并行执行;
    // 信号量限流控制同时驻留内存的章测量布局数;awaitAll 按发起顺序取结果,与串行结果一致
    suspend fun buildSpecs(book: ReaderBook, fullText: String, typo: ResolvedTypography): List<PageSpec> =
        coroutineScope {
            val specs = ArrayList<PageSpec>()
            specs += PageSpec(PageKind.COVER, -1, 0, 1, 0L, "")
            val chapters = book.chapters
            if (chapters.isEmpty()) {
                // 无章节书: 整本为单章,书名作章名
                specs += chapterSpecs(book, fullText, typo, book.title, 0L, 0)
            } else {
                val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val permits = Semaphore(parallelism)
                val perChapter = chapters.indices.map { c ->
                    async {
                        permits.withPermit {
                            ensureActive()
                            chapterSpecs(book, fullText, typo, chapters[c].title, chapters[c].startChar, c)
                        }
                    }
                }
                perChapter.awaitAll().forEach { specs += it }   // 发起顺序 = 章序,结果确定性不变
            }
            specs += PageSpec(PageKind.BACK, book.chapters.size, 0, 1, book.totalChars, "")
            specs
        }

    // 一章 → 页描述列表。章测量 layout 仅在此处存在,用完即弃
    private fun chapterSpecs(
        book: ReaderBook,
        fullText: String,
        typo: ResolvedTypography,
        title: String,
        chapterStartGlobal: Long,
        chapterIndex: Int
    ): List<PageSpec> {
        val (body, stripped) = ChapterComposer.stripLeadingTitle(
            chapterText(fullText, book, chapterIndex), title
        )
        val bodyZero = chapterStartGlobal + stripped   // 剥后正文的零点(全书坐标)
        val composed = ChapterComposer.compose(title, body, typo)
        val measure = Typography.buildLayout(composed, typo)
        val windows = PaginationEngine.trimTrailingBlank(
            PaginationEngine.splitPages(StaticLayoutLineSource(measure), typo.textHeight)
        ) { slice ->
            composed.subSequence(
                measure.getLineStart(slice.startLine),
                measure.getLineEnd(slice.endLineExclusive - 1)
            ).isBlank()
        }
        val bodyStart = ChapterComposer.bodyStart(title.length)
        return windows.mapIndexed { i, slice ->
            // 页首合成偏移 → 全书偏移: 标题区内的页(章首页)锚定章起点;正文页从正文零点换算
            val s = measure.getLineStart(slice.startLine)
            val global = if (s < bodyStart) chapterStartGlobal else bodyZero + (s - bodyStart)
            PageSpec(PageKind.CONTENT, chapterIndex, i, windows.size, global, title)
        }
    }

    // 物化一页。next = 同章下一页(其页首偏移即本页页末);null=本章末页。
    // 拖拽预览与落账共用物化结果,保证看到的==翻到的
    fun materialize(
        book: ReaderBook,
        fullText: String,
        spec: PageSpec,
        next: PageSpec?,
        typo: ResolvedTypography
    ): BookPage {
        if (spec.kind != PageKind.CONTENT) {
            val text = if (spec.kind == PageKind.COVER) book.title else "最后一页了"
            return BookPage(spec, Typography.buildVirtualLayout(text, typo), "", "")
        }
        val chapter = book.chapters.getOrNull(spec.chapterIndex)   // 无章节书: 无章表,起点按 0
        val chapterStart = chapter?.startChar ?: 0L
        val bodyStart = ChapterComposer.bodyStart(spec.chapterTitle.length)
        val (body, stripped) = ChapterComposer.stripLeadingTitle(
            chapterText(fullText, book, spec.chapterIndex), spec.chapterTitle
        )
        val bodyZero = chapterStart + stripped
        val composed = ChapterComposer.compose(spec.chapterTitle, body, typo)
        // 页合成区间: 章首页从标题首字符(0)起;其余页由全书偏移反推,页末=下一页页首
        val s = if (spec.chapterPageIndex == 0) 0
        else (spec.globalCharOffset - bodyZero + bodyStart).toInt()
        val e = next
            ?.let { (it.globalCharOffset - bodyZero + bodyStart).toInt() }
            ?: composed.length
        // 页尾一致性: 断行用贪心策略(SIMPLE),行首只取决于前文,单页重排与整章测量的
        // 断行完全一致,e 必为本页布局的行尾,不会出现截断点甩到本页末尾的孤字
        val hasBlank = ChapterComposer.hasBlankLine(composed, bodyStart, composed.length)
        val (text, lead) = pageText(composed, s, e, spec.chapterTitle.length, typo, hasBlank)
        if (DBG) android.util.Log.d(
            "ReaderJustify",
            "mat ch=${spec.chapterIndex} p=${spec.chapterPageIndex} s=$s e=$e " +
                "tail='${text.takeLast(10)}'"
        )
        val layout = Typography.buildLayout(text, typo)
        // 垂直匀齐: 页底剩余空白摊到本页可见行的行距(单行封顶 1 字高)。
        // setLineSpacing 只增行间空隙、不改断行,本页包含的行与分页目录一致;
        // 章末页不分配,空白自然留在页底
        var topAdd = 0f
        var finalLayout = layout
        if (spec.chapterPageIndex < spec.chapterPageCount - 1) {
            val (extra, top) = PaginationEngine.justifyLineSpacing(
                layout.height, layout.lineCount, typo.textHeight, typo.fontPx
            ) { e2 -> Typography.buildLayout(text, typo, e2).height }
            if (extra > 0f) {
                finalLayout = Typography.buildLayout(text, typo, extra)
                topAdd = top
            }
        }
        val percent = percentOf(book, spec)
        val label = "第 ${spec.chapterPageIndex + 1}/${spec.chapterPageCount} 页 · ${(percent * 100).roundToInt()}%"
        val last = finalLayout.lineCount - 1
        val tailLine = buildTailLine(
            PageBuild(text, lead, finalLayout, TailInfo(last, lead + finalLayout.getLineEnd(last))),
            composed, e, spec, typo, finalLayout
        )
        return BookPage(spec, finalLayout, spec.chapterTitle, label, topAdd, tailLine)
    }

    private class PageBuild(
        val text: CharSequence,
        val lead: Int,           // 页文本起点在合成文本中的下标: 文本下标 i ↔ 合成下标 lead+i
        val layout: StaticLayout,
        val tail: TailInfo?
    )

    private class TailInfo(val vLine: Int, val vEnd: Int)

    // 页尾可见末行处理。两种职责:
    //  1) 行内带上下文字符(行尾超出 e)时,盖掉重画为仅含 [行首, e) 的字符(隐藏下一页开头的字);
    //  2) 段中截断的末行做两端对齐拉伸(段落末行/标题行/不可拉伸的短行保持系统参差,不重画)
    private fun buildTailLine(
        build: PageBuild,
        composed: CharSequence,
        e: Int,
        spec: PageSpec,
        typo: ResolvedTypography,
        layout: StaticLayout
    ): LastLineDraw? {
        val tail = build.tail ?: return null
        val lead = build.lead
        val v = tail.vLine
        val ls = layout.getLineStart(v)
        val le = layout.getLineEnd(v)
        if (le <= ls) return null
        val visibleEnd = (e - lead).coerceIn(ls, le)
        val lineStr = build.text.substring(ls, visibleEnd)
        if (lineStr.isEmpty()) return null
        val hasHidden = le > visibleEnd   // 行内有上下文字符需要隐藏
        val left = layout.getLineLeft(v)
        var segments: List<Pair<String, Float>>? = null
        if (typo.justify && !lineStr.endsWith("\n") &&
            !(spec.chapterPageIndex == 0 && ls < spec.chapterTitle.length)
        ) {
            // 段落末行判定: 可见行尾(合成文本下标)之后是 '\n' 或已到章末 → 保持系统参差
            val endInComposed = lead + visibleEnd
            if (endInComposed < composed.length && composed[endInComposed] != '\n') {
                val target = typo.textWidth - left
                val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = typo.fontPx }
                segments = PaginationEngine.justifySegments(lineStr, target, typo.fontPx) { paint.measureText(it) }
            }
        }
        if (!hasHidden && segments == null) return null   // 布局本身正确且无需拉伸
        val segs = segments ?: listOf(lineStr to left)    // 自然宽度重画,只为隐藏上下文字符
        return LastLineDraw(
            segs.map { LineSeg(it.first, it.second) },
            layout.getLineTop(v).toFloat(),
            layout.getLineBaseline(v).toFloat(),
            layout.getLineBottom(v).toFloat()
        )
    }

    // 全书偏移 → 全局页号: 二分找最后一个页首偏移 <= 目标的页
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

    // 页文本 = 合成文本 [s, e) 的纯字符(丢弃测量 span,避免跨页裁剪污染)+ 本页样式重建:
    //  1) 标题样式延续到标题跨页的续页; 2) 段首缩进只给真正的段首(页首接段中不缩进);
    //  3) 页末行是段中行时由 materialize 手工拉伸对齐(见 buildTailLine);
    // 返回 (页文本, 文本起点在合成文本中的下标 lead: 文本下标 i ↔ 合成下标 lead+i)
    private fun pageText(
        composed: CharSequence,
        start: Int,
        end: Int,
        titleLength: Int,
        typo: ResolvedTypography,
        chapterHasBlank: Boolean
    ): Pair<CharSequence, Int> {
        var e = end
        while (e > start && composed[e - 1] == '\n') e--   // 去章末残留空行
        var lead = start
        while (lead < e && composed[lead] == '\n') lead++   // 去页首空行
        val sb = SpannableStringBuilder(composed.subSequence(lead, e).toString())
        val base = lead
        val titleEnd = titleLength
        val bodyStart = ChapterComposer.bodyStart(titleLength)
        if (base < titleEnd) {
            val spanEnd = (titleEnd - base).coerceAtMost(sb.length)
            sb.setSpan(RelativeSizeSpan(ChapterComposer.TITLE_SCALE), 0, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (typo.indentPx > 0f) {
            var paraStart = 0
            var i = 0
            while (i <= sb.length) {
                if (i == sb.length || sb[i] == '\n') {
                    val trueStart = paraStart > 0 || base == 0 || composed[base - 1] == '\n'
                    if ((paraStart until i).any { !sb[it].isWhitespace() } &&
                        base + paraStart >= bodyStart && trueStart && !ChapterComposer.leadingIndented(sb, paraStart)
                    ) {
                        sb.setSpan(
                            LeadingMarginSpan.Standard(typo.indentPx.toInt(), 0),
                            paraStart, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    paraStart = i + 1
                }
                i++
            }
        }
        // 段距/空行压缩: 正文区(章首页需跳过标题块)内生效
        val spacingFrom = (bodyStart - base).coerceAtLeast(0)
        if (spacingFrom < sb.length) {
            ChapterComposer.applyParagraphSpacing(sb, spacingFrom, sb.length, typo, { q ->
                (q == spacingFrom && (base == 0 || composed[base - 1] == '\n')) || (q > 0 && sb[q - 1] == '\n')
            }, chapterHasBlank)
        }
        return sb to lead
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
