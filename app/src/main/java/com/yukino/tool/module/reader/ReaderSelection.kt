package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.ReaderBook

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

// 选区 → 可复制文本: 按 BookContent 裁切(txt 源文本子串 / epub 章区间切片),章界补换行
fun selectionText(content: com.yukino.tool.module.reader.common.BookContent, sel: ReaderSelection): String =
    content.textAt(sel.startGlobal, sel.endGlobal)
