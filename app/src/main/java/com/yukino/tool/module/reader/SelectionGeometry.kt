package com.yukino.tool.module.reader

import kotlin.math.abs

// 文字选择的几何层: 命中测试 / 选词 / 选区可视化。纯函数,measure 注入,本地单测覆盖。
//
// 坐标系约定: 所有入参与返回值均为"版心坐标系"——与 DrawLine 同一空间
// (x 相对版心左缘,即含首行缩进但不含页边距;y 相对内容区顶,即 DrawLine.baseline 的坐标系)。
// 调用方(手势层)负责屏幕坐标的两步换算: x-marginPx、y-contentTop。
//
// 字符的全书偏移: 行内第 i 字符 = DrawLine.lineStartGlobal + i(正文区线性映射,标题行锚定章起点)。
// 选区即全书偏移区间,跨页/跨章统一,本层不需要任何跨页逻辑
object SelectionGeometry {

    // 字体度量 + 测量函数: 正文/标题两套字号,measure(text, title) 按行类别取
    class Metrics(
        val bodyAscent: Float,
        val bodyDescent: Float,
        val titleAscent: Float,
        val titleDescent: Float,
        val measure: (String, Boolean) -> Float
    )

    // 选区可视化(版心坐标,供绘制层与手柄定位)
    class SelRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    class SelectionVisual(
        val rects: List<SelRect>,
        val startHandle: SelRect,   // 首柄锚: 选中首字符处的窄条(top..bottom 为手柄上下文范围)
        val endHandle: SelRect,
        val extendsTop: Boolean,    // 选区延续到上一页(边缘指示)
        val extendsBottom: Boolean
    ) {
        // 命中(含 slop 扩边)是否落在选区内: 选区内单击弹工具栏,选区外单击清除
        fun contains(x: Float, y: Float, slop: Float): Boolean = rects.any {
            x >= it.left - slop && x <= it.right + slop && y >= it.top - slop && y <= it.bottom + slop
        }
    }

    // ---- 垂直定位: cy → 行下标。行竖直区间 = [baseline-ascent, baseline+descent],
    // 命中区间直接返回;行间空隙/上下越界取中心最近的一行
    internal fun locateLine(page: BookPage, cy: Float, m: Metrics): Int? {
        val lines = page.lines
        if (lines.isEmpty()) return null
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in lines.indices) {
            val ln = lines[i]
            val a = if (ln.title) m.titleAscent else m.bodyAscent
            val d = if (ln.title) m.titleDescent else m.bodyDescent
            val top = ln.baseline - a
            val bottom = ln.baseline + d
            if (cy >= top && cy <= bottom) return i
            val dist = abs(cy - (top + bottom) / 2f)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    // ---- 水平定位: cx → 行内字符下标。以字符中心为界就近吸附。
    // 两端对齐行必须先按 seg.x 定位分段再段内二分——justify 行字符 x ≠ 自然宽度
    internal fun charOffsetInLine(ln: DrawLine, cx: Float, m: Metrics): Int {
        val measure = { t: String -> m.measure(t, ln.title) }
        val segs = ln.segments
        if (segs == null) {
            val width = measure(ln.text)
            return snapIndex(ln.text, (cx - ln.x).coerceIn(0f, width), measure)
        }
        val rel = cx - ln.x
        var acc = 0
        for (k in segs.indices) {
            val seg = segs[k]
            val segWidth = measure(seg.text)
            val segEnd = seg.x + segWidth
            if (k < segs.lastIndex && rel >= segEnd) {   // 落到下一段
                acc += seg.text.length
                continue
            }
            val local = (rel - seg.x).coerceIn(0f, segWidth)
            return acc + snapIndex(seg.text, local, measure)
        }
        return ln.text.length
    }

    // 二分找最大 i 使 measure(前缀 i) <= x,再按字符中心吸附
    private fun snapIndex(text: String, x: Float, measure: (String) -> Float): Int {
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(text.substring(0, mid)) <= x) lo = mid else hi = mid - 1
        }
        if (lo >= text.length) return text.length
        val charWidth = measure(text.substring(lo, lo + 1))
        return if (x <= measure(text.substring(0, lo)) + charWidth / 2f) lo else lo + 1
    }

    // ---- 完整命中: 版心坐标 → (行下标, 行内字符下标)
    fun hit(page: BookPage, cx: Float, cy: Float, m: Metrics): Pair<Int, Int>? {
        val li = locateLine(page, cy, m) ?: return null
        val ln = page.lines[li]
        return li to charOffsetInLine(ln, cx, m)
    }

    // 行/字符下标 → 全书偏移
    fun globalAt(page: BookPage, lineIdx: Int, charIdx: Int): Long {
        val ln = page.lines[lineIdx]
        return ln.lineStartGlobal + charIdx.coerceIn(0, ln.text.length)
    }

    // ---- 选词: ASCII 字母/数字连续串整串,其余(CJK/标点/空白)单字符。
    // 返回全书偏移区间 [start, end)
    fun wordRange(page: BookPage, lineIdx: Int, charIdx: Int): Pair<Long, Long> {
        val ln = page.lines[lineIdx]
        val t = ln.text
        if (t.isEmpty()) return ln.lineStartGlobal to ln.lineStartGlobal
        val i = charIdx.coerceIn(0, t.lastIndex)
        fun asciiWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
        var l = i
        var r = i
        if (asciiWord(t[i])) {
            while (l > 0 && asciiWord(t[l - 1])) l--
            while (r < t.lastIndex && asciiWord(t[r + 1])) r++
        }
        return (ln.lineStartGlobal + l) to (ln.lineStartGlobal + r + 1)
    }

    // ---- 选区可视化: 选区 ∩ 本页 → 高亮矩形 + 手柄锚 + 跨页延续标志。
    // 首尾留 2px 纵向余量,视觉上包住字形
    fun visual(page: BookPage, sel: ReaderSelection, m: Metrics): SelectionVisual? {
        val lines = page.lines
        if (lines.isEmpty()) return null
        val rects = ArrayList<SelRect>()
        var startAnchor: SelRect? = null
        var endAnchor: SelRect? = null
        for (ln in lines) {
            val lineEnd = ln.lineStartGlobal + ln.text.length
            if (lineEnd <= sel.startGlobal || ln.lineStartGlobal >= sel.endGlobal) continue
            val a = (sel.startGlobal - ln.lineStartGlobal).coerceIn(0, ln.text.length.toLong()).toInt()
            val b = (sel.endGlobal - ln.lineStartGlobal).coerceIn(0, ln.text.length.toLong()).toInt()
            if (b <= a) continue
            val measure = { t: String -> m.measure(t, ln.title) }
            val x0 = charLeft(ln, a, measure)
            val x1 = charLeft(ln, b, measure)
            val ascent = if (ln.title) m.titleAscent else m.bodyAscent
            val descent = if (ln.title) m.titleDescent else m.bodyDescent
            val rect = SelRect(x0, ln.baseline - ascent - 2f, x1, ln.baseline + descent + 2f)
            if (startAnchor == null) startAnchor = SelRect(x0, rect.top, x0, rect.bottom)
            endAnchor = SelRect(x1, rect.top, x1, rect.bottom)
            rects += rect
        }
        if (rects.isEmpty()) return null
        val first = lines.first()
        val last = lines.last()
        val extendsTop = sel.startGlobal < first.lineStartGlobal
        val extendsBottom = sel.endGlobal > last.lineStartGlobal + last.text.length
        return SelectionVisual(rects, startAnchor!!, endAnchor!!, extendsTop, extendsBottom)
    }

    // 行内第 idx 字符的左缘 x(第 text.length 个 = 行尾右缘)。justify 行走分段
    private fun charLeft(ln: DrawLine, idx: Int, measure: (String) -> Float): Float {
        val segs = ln.segments
        if (segs == null) return ln.x + measure(ln.text.substring(0, idx))
        var acc = 0
        for (seg in segs) {
            if (idx <= acc + seg.text.length) {
                return ln.x + seg.x + measure(seg.text.substring(0, idx - acc))
            }
            acc += seg.text.length
        }
        val lastSeg = segs.last()
        return ln.x + lastSeg.x + measure(lastSeg.text)
    }
}
