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
