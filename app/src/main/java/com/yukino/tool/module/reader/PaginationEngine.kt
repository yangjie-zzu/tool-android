package com.yukino.tool.module.reader

import android.text.StaticLayout

// 一页 = 连续的行区间 [startLine, endLineExclusive)
data class PageSlice(val startLine: Int, val endLineExclusive: Int) {
    fun containsLine(line: Int): Boolean = line in startLine until endLineExclusive
}

// 行信息抽象: 切页算法只依赖它,不依赖 StaticLayout(便于本地单测用假实现)
interface LineSource {
    val lineCount: Int
    fun top(line: Int): Int
    fun bottom(line: Int): Int
    fun start(line: Int): Int
}

class StaticLayoutLineSource(private val layout: StaticLayout) : LineSource {
    override val lineCount: Int get() = layout.lineCount
    override fun top(line: Int): Int = layout.getLineTop(line)
    override fun bottom(line: Int): Int = layout.getLineBottom(line)
    override fun start(line: Int): Int = layout.getLineStart(line)
}

object PaginationEngine {

    // 整章单布局 + 行窗口切页: 逐行累高,超出页高即翻页。段落跨页是自然行为。
    fun splitPages(lines: LineSource, textHeight: Int): List<PageSlice> {
        if (lines.lineCount == 0) return emptyList()
        val pages = ArrayList<PageSlice>()
        var line = 0
        while (line < lines.lineCount) {
            val top = lines.top(line)
            var end = line
            while (end < lines.lineCount && lines.bottom(end) - top <= textHeight) end++
            if (end == line) end = line + 1 // 防御: 单行超过一页高时强制翻
            pages.add(PageSlice(line, end))
            line = end
        }
        return pages
    }

    // 垂直匀齐: 页底剩余空白摊到本页每行行距。relayout(extra) 返回加增量后的排版高度。
    // 返回 (每行增量, 页首下移): 按行数分摊留安全余量;单行增量封顶 cap;
    // 重排后溢出则增量减半再试一次;仍溢出返回 (0,0) 回退基础行距;
    // 分摊后的零头由 topAdd 上下各半,保持居中观感。章末页由调用方短路,不进本函数
    fun justifyLineSpacing(
        baseHeight: Int,
        lineCount: Int,
        textHeight: Int,
        cap: Float,
        relayout: (Float) -> Int
    ): Pair<Float, Float> {
        if (lineCount <= 0) return 0f to 0f
        val leftover = textHeight - baseHeight
        if (leftover <= 0) return 0f to 0f
        var extra = (leftover.toFloat() / lineCount).coerceAtMost(cap)
        var height = relayout(extra)
        var left = textHeight - height
        if (left < 0) {
            extra /= 2f
            height = relayout(extra)
            left = textHeight - height
        }
        if (left < 0) return 0f to 0f   // 仍溢出: 回退基础行距,零头交还页底
        return extra to left / 2f
    }

    // 页末行手工两端对齐: 把 (targetWidth-自然宽度) 均分到字间/词间。
    // 西文按空格分词,中文逐字;单处间距超过 maxGap(一般传字号,末行字太少)或为负
    // 视为不可拉伸,返回 null 保持系统默认参差。返回 (片段, 相对行首的 x 偏移) 列表
    fun justifySegments(
        line: String,
        targetWidth: Float,
        maxGap: Float,
        measure: (String) -> Float
    ): List<Pair<String, Float>>? {
        if (line.isEmpty()) return null
        val hasSpace = line.any { it == ' ' }
        val tokens = if (hasSpace) line.split(' ').filter { it.isNotEmpty() } else line.map { it.toString() }
        if (tokens.size < 2) return null
        val natural = tokens.sumOf { measure(it).toDouble() }.toFloat()
        val per = (targetWidth - natural) / (tokens.size - 1)
        if (per <= 0f || per > maxGap) return null
        val out = ArrayList<Pair<String, Float>>(tokens.size)
        var x = 0f
        tokens.forEach { t ->
            out += t to x
            x += measure(t) + per
        }
        return out
    }

    // 偏移 → 行(由 StaticLayout.getLineForOffset 完成) → 页: 行区间二分,找最后一个 startLine <= line 的页
    fun pageForOffset(pages: List<PageSlice>, line: Int): Int {
        var lo = 0
        var hi = pages.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pages[mid].startLine <= line) lo = mid else hi = mid - 1
        }
        return lo
    }

    // 去掉尾部没有可见字符的页: 章末多余换行会被切成近乎空白的尾页,
    // 导致跨章拖拽预览(末页)与落账定位不一致。全空白时保留末页,避免无页可显示
    fun trimTrailingBlank(pages: List<PageSlice>, isBlank: (PageSlice) -> Boolean): List<PageSlice> {
        var last = pages.lastIndex
        while (last >= 0 && isBlank(pages[last])) last--
        if (last == pages.lastIndex) return pages
        return if (last < 0) pages.takeLast(1) else pages.take(last + 1)
    }
}
