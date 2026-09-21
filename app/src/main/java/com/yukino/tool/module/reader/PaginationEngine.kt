package com.yukino.tool.module.reader

import kotlin.math.ceil

// 一页 = 连续的行区间 [startLine, endLineExclusive)
data class PageSlice(val startLine: Int, val endLineExclusive: Int) {
    fun containsLine(line: Int): Boolean = line in startLine until endLineExclusive
}

object PaginationEngine {

    // 网格对齐原语: 值向上取整到网格整数倍。行高、段前距都落在网格上,
    // 取整只增不减,字形不被裁
    fun gridCeil(v: Float, gridPx: Int): Int = ceil(v / gridPx).toInt() * gridPx

    // 版心贴合(满页排版): 正文行高从基准值放大为"版心高 ÷ 每页行数"(对齐网格),
    // 每页行数固定,页底余数从"版心 mod 行高"(可达一个行高)缩到每行网格取整的累计级。
    // N = floor(版心/基准行高);放大后行高 ≥ 基准(字形不裁)、N × 行高 ≤ 版心(不溢出)。
    // 全章行高一致 → 跨页行位对齐保持
    fun fitPitch(textHeight: Int, pitchBase: Int, gridPx: Int): Int {
        if (pitchBase <= 0 || textHeight < pitchBase) return pitchBase
        val fitted = textHeight / (textHeight / pitchBase) / gridPx * gridPx
        return if (fitted >= pitchBase) fitted else pitchBase
    }

    // 行窗口切页: 行高逐行累加,超出页高即翻页。段落跨页是自然行为。
    // 页首行豁免段前距(paraAbove 只在页中生效): 窗口开头的空行(各 1 格)之后,
    // 首个非空行若是段首行,按净高(行高-段前距)计入并顶格——"新起一页"本身完成段落分隔。
    // 豁免判定不能只看窗口首行: 一个 1 格的空行恰好成为窗口首行时会"截胡"豁免,
    // 段前距留白重新出现在页顶
    fun splitPages(lines: List<TextLine>, textHeight: Int): List<PageSlice> {
        if (lines.isEmpty()) return emptyList()
        val pages = ArrayList<PageSlice>()
        var line = 0
        while (line < lines.size) {
            val start = line
            // 页首首个非空行(跳过窗口开头的空行): 其段前距豁免
            var head = start
            while (head < lines.size && lines[head].kind == LineKind.BLANK) head++
            var height = 0
            var end = line
            while (end < lines.size) {
                val occupied = if (end == head) lines[end].pitch - lines[end].paraAbove else lines[end].pitch
                if (height + occupied > textHeight) break
                height += occupied
                end++
            }
            if (end == line) end = line + 1 // 防御: 首行净高超过一页高时强制翻
            pages.add(PageSlice(start, end))
            line = end
        }
        return pages
    }

    // 去掉尾部没有可见字符的页: 章末多余换行会被切成近乎空白的尾页,
    // 导致跨章拖拽预览(末页)与落账定位不一致。全空白时保留末页,避免无页可显示
    fun trimTrailingBlank(pages: List<PageSlice>, isBlank: (PageSlice) -> Boolean): List<PageSlice> {
        var last = pages.lastIndex
        while (last >= 0 && isBlank(pages[last])) last--
        if (last == pages.lastIndex) return pages
        return if (last < 0) pages.takeLast(1) else pages.take(last + 1)
    }

    // 行文本 → 拉伸词元: 宽字符(CJK/全角标点)逐字成元,西文按词累积,空格粘在前词尾
    // (拉伸只增词元间隙,不丢空格自身宽度,西文词不会挤在一起)
    fun tokenize(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in line) {
            when {
                c == ' ' -> {
                    sb.append(c)
                    out += sb.toString()
                    sb.setLength(0)
                }
                isWide(c) -> {
                    if (sb.isNotEmpty()) {
                        out += sb.toString()
                        sb.setLength(0)
                    }
                    out += c.toString()
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    // 宽字符起点(0x2E80 CJK 部首起,含汉字/假谚/全角标点): 拉伸时逐字独立成元
    internal fun isWide(c: Char): Boolean = c.code >= 0x2E80

    // 行两端对齐: 把 (targetWidth-自然宽度) 均分到词元间隙。
    // 单处间距超过 maxGap(一般传字号)或为负视为不可拉伸,返回 null 保持自然宽。
    // 返回 (词元, 相对行首的 x 偏移) 列表
    fun justifySegments(
        line: String,
        targetWidth: Float,
        maxGap: Float,
        measure: (String) -> Float
    ): List<Pair<String, Float>>? {
        val tokens = tokenize(line)
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
}
