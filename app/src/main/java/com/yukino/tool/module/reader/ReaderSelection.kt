package com.yukino.tool.module.reader

// 文字选择状态: 全书字符偏移区间 [startGlobal, endGlobal)。
// 锚定全书偏移 → 跨页/跨章统一,且字号重排后位置不漂移(与 Progress 同一设计)。
// anchorIsStart: 正在拖动的是首手柄(反向扩展时翻转,拖动端永不越到固定端另一侧之外)
data class ReaderSelection(
    val startGlobal: Long,
    val endGlobal: Long,
    val anchorIsStart: Boolean
) {
    init {
        require(startGlobal <= endGlobal) { "selection start > end" }
    }

    // 拖动端更新到 v: 正向延伸直接移动;越过固定端则选区翻转、拖动端切换(系统选择语义)。
    // v 恰好压在固定端上时保持原选区——若允许 start==end 会产生空选区,
    // 可视层算不出高亮,整个选择 UI 会"消失"卡死
    fun withAnchor(v: Long): ReaderSelection = when {
        anchorIsStart && v < endGlobal -> copy(startGlobal = v)
        anchorIsStart && v > endGlobal -> copy(startGlobal = endGlobal, endGlobal = v, anchorIsStart = false)
        !anchorIsStart && v > startGlobal -> copy(endGlobal = v)
        !anchorIsStart && v < startGlobal -> copy(endGlobal = startGlobal, startGlobal = v, anchorIsStart = true)
        else -> this
    }

    fun contains(v: Long): Boolean = v in startGlobal..endGlobal

    fun isEmpty(): Boolean = startGlobal == endGlobal
}

// 选区 → 可复制文本: 全书文本裁切,章界处补换行(章首剥掉的原标题行不落在任何正文页上,
// 选区永不覆盖它,子串天然干净;相邻章内容在源文本中是连续的,补 \n 还原段落分隔)
fun selectionText(book: ReaderBook, fullText: String, sel: ReaderSelection): String {
    val s = sel.startGlobal.toInt().coerceIn(0, fullText.length)
    val e = sel.endGlobal.toInt().coerceIn(s, fullText.length)
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
