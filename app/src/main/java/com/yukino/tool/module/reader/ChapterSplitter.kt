package com.yukino.tool.module.reader

// 章节识别: 逐行匹配标题正则,行超长不误判;命中数越界降级为整本单章
object ChapterSplitter {

    private val TITLE_PATTERNS = listOf(
        Regex("^第[零一二三四五六七八九十百千万〇两0-9]{1,12}[章回节卷幕]"),
        Regex("^Chapter\\s+\\d+", RegexOption.IGNORE_CASE),
        Regex("^(序章|序言|楔子|尾声|后记|番外)")
    )

    // 精校版标题行尾的元数据块,如 [本章字数：5594　最新更新时间：2007-07-05 11:45:36.0]
    private val METADATA_TAIL = Regex("\\s*\\[[^\\[\\]]{0,60}\\]\\s*$")

    private const val MAX_TITLE_LEN = 50
    private const val MIN_CHAPTERS = 3
    private const val MAX_CHAPTERS = 2000

    // 章末签名行(正文标题后紧跟的"第X章 标题  疯癫道男")与正文标题相距通常仅几十~几百字符
    private const val SIGNATURE_MAX_GAP = 2000

    private class Hit(val start: Int, val key: String, val title: String)

    // 返回按 startChar 升序的章节索引;识别不出时返回单个整本章节(标题取书名)
    fun split(text: String, bookTitle: String): List<ChapterIndex> {
        val hits = ArrayList<Hit>()
        var lineStart = 0
        var i = 0
        while (i <= text.length) {
            if (i == text.length || text[i] == '\n') {
                matchTitle(text.substring(lineStart, i))?.let { (key, title) ->
                    hits.add(Hit(lineStart, key, title))
                }
                lineStart = i + 1
            }
            i++
        }

        if (hits.size < MIN_CHAPTERS || hits.size > MAX_CHAPTERS) {
            return listOf(ChapterIndex(bookTitle, 0L))
        }

        // 章末签名行归并: 与前一个保留命中章号相同、且紧邻的命中不是新章
        val deduped = ArrayList<Hit>(hits.size)
        for (h in hits) {
            val prev = deduped.lastOrNull()
            if (prev != null && prev.key == h.key && h.start - prev.start <= SIGNATURE_MAX_GAP) continue
            deduped.add(h)
        }

        // 目录页过滤: "目录"标记后、标题在后文重复出现的命中都是目录行,整块丢弃
        val real = dropTocEntries(text, deduped)
        if (real.isEmpty()) {
            return listOf(ChapterIndex(bookTitle, 0L))
        }

        val chapters = ArrayList<ChapterIndex>(real.size + 1)
        if (real[0].start > 0) chapters.add(ChapterIndex(bookTitle, 0L))
        real.forEach { h -> chapters.add(ChapterIndex(h.title, h.start.toLong())) }
        return chapters
    }

    // 目录标记行("目录"/"目录："/"【目录】"等)之后,标题在后文重复出现的命中都是目录行
    // (目录列一遍、正文写一遍);第一个"标题不再重复"的命中即正文首章。
    // 无目录标记 → 原样返回(保守,不做间距等启发式)
    private fun dropTocEntries(text: String, hits: List<Hit>): List<Hit> {
        val markerEnd = tocMarkerLineEnd(text, hits) ?: return hits
        var i = hits.indexOfFirst { it.start >= markerEnd }
        if (i < 0) return hits
        while (i < hits.size) {
            val t = hits[i].title.trim()
            val duplicatedLater = (i + 1 until hits.size).any { hits[it].title.trim() == t }
            if (!duplicatedLater) break
            i++
        }
        return hits.subList(i, hits.size)
    }

    // 目录标记行的结束偏移;只认最后一个命中之前出现的第一个标记,无则 null
    private fun tocMarkerLineEnd(text: String, hits: List<Hit>): Int? {
        val lastHitStart = hits.last().start
        var lineStart = 0
        var i = 0
        while (i <= text.length) {
            if (i == text.length || text[i] == '\n') {
                val line = text.substring(lineStart, i).trim()
                    .trim(' ', '　', '【', '】', '：', ':', '〔', '〕', '■', '●', '*')
                if (lineStart < lastHitStart && (line == "目录" || (line.startsWith("目录") && line.length <= 8))) {
                    return if (i < text.length) i + 1 else i
                }
                lineStart = i + 1
            }
            i++
        }
        return null
    }

    // 返回 (章号key, 展示标题);非标题行返回 null。
    // 先剥离行尾字数/更新时间等元数据块再判长度(剥离后仍超长的不算标题);
    // key 用于章末签名行归并(与正文标题章号相同、紧邻出现)
    private fun matchTitle(line: String): Pair<String, String>? {
        var t = line.trim()
        if (t.isEmpty()) return null
        while (true) {
            val m = METADATA_TAIL.find(t) ?: break
            t = t.substring(0, m.range.first).trim()
        }
        if (t.isEmpty() || t.length > MAX_TITLE_LEN) return null
        val key = TITLE_PATTERNS.firstNotNullOfOrNull { it.find(t)?.value } ?: return null
        return key to t
    }
}
