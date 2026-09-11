package com.yukino.tool.module.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {

    @Test
    fun `常规第X章切分`() {
        val text = """
            开头介绍内容,位于第一章之前。
            第一章 起点
            正文一
            第二章 转折
            正文二
            第三章 高潮
            正文三
            尾声
        """.trimIndent()
        val chapters = ChapterSplitter.split(text, "书名")
        assertEquals(5, chapters.size)   // 书名(第一章之前的正文) + 三章 + 尾声
        assertEquals("书名", chapters[0].title)   // 第一个标题前有正文
        assertEquals(0L, chapters[0].startChar)
        assertEquals("第一章 起点", chapters[1].title)
        assertTrue(text.indexOf("第一章 起点").toLong() == chapters[1].startChar)
    }

    @Test
    fun `数字与中文数字章节号都识别`() {
        // 正文行不以"第X章"开头,避免误命中
        val text = (1..3).joinToString("\n\n") { i ->
            "第${i}章 测试\n这是第${i}章的正文内容"
        }
        val chapters = ChapterSplitter.split(text, "书名")
        assertEquals(3, chapters.size)
        assertEquals("第1章 测试", chapters[0].title)
    }

    @Test
    fun `Chapter英文标题识别`() {
        val text = (1..3).joinToString("\n\n") { i -> "Chapter $i\nContent $i" }
        val chapters = ChapterSplitter.split(text, "Book")
        assertEquals(3, chapters.size)
    }

    @Test
    fun `命中数不足降级单章`() {
        val text = "第一章 起点\n正文A\n正文B"
        val chapters = ChapterSplitter.split(text, "书名")
        assertEquals(1, chapters.size)
        assertEquals("书名", chapters[0].title)
        assertEquals(0L, chapters[0].startChar)
    }

    @Test
    fun `标题行超长不误判`() {
        // 超过 50 字符的行即使以"第X章"开头也不算标题
        val longLine = "第一章 " + "这是一段很长的正文内容并非标题" .repeat(6)
        val text = (1..3).joinToString("\n\n") { i -> "第${i}章 标题\n正文" } + "\n\n" + longLine
        val chapters = ChapterSplitter.split(text, "书名")
        assertEquals(3, chapters.size)
    }

    @Test
    fun `带字数元数据的标题行识别并剥离元数据`() {
        // 精校版: 标题行尾带 [本章字数…] 元数据,原行超 50 字,剥离后应命中且标题干净
        fun ch(i: Int, extra: String = "") =
            "第${i}章 标题$extra　[本章字数：5594　最新更新时间：2007-07-05 11:45:36.0]\n第${i}章的正文内容。"
        val text = ch(1) + "\n\n" + ch(2) + "\n\n" + ch(3)
        val chapters = ChapterSplitter.split(text, "书名")
        assertEquals(3, chapters.size)
        assertEquals("第1章 标题", chapters[0].title)
    }

    @Test
    fun `章末签名行与正文章归并不产生幽灵章节`() {
        // 正文标题(带元数据)后紧跟同章号的签名行,应归并为一个章节锚点
        fun ch(i: Int, tail: String = "") =
            "第${i}章 标题　[本章字数：5594　最新更新时间：2007-07-05 11:45:36.0]\n正文。\n$tail"
        val text = ch(1) + "\n" + ch(2, "第2章 标题     疯癫道男\n") + "\n" + ch(3)
        val chapters = ChapterSplitter.split(text, "书名")
        assertEquals(3, chapters.size)
        assertEquals("第2章 标题", chapters[1].title)
    }

    @Test
    fun `序章楔子等特殊标题识别`() {
        val text = "序章\n引子内容\n\n第一章 起点\n正文\n\n第二章 进展\n正文\n\n楔子\n尾注"
        val chapters = ChapterSplitter.split(text, "书名")
        assertTrue(chapters.any { it.title == "序章" })
        assertTrue(chapters.any { it.title == "楔子" })
    }

    @Test
    fun `空文本返回单章`() {
        val chapters = ChapterSplitter.split("", "书名")
        assertEquals(1, chapters.size)
        assertEquals(0L, chapters[0].startChar)
    }

    @Test
    fun `startChar指向标题行首`() {
        val title = "第二章 转折"
        val text = "第一章 起点\n正文一\n" + title + "\n正文二\n\n第三章 结局\n正文三"
        val chapters = ChapterSplitter.split(text, "书名")
        assertEquals(3, chapters.size)
        val start = chapters[1].startChar.toInt()
        assertEquals(title, text.substring(start, start + title.length))
    }

    @Test
    fun `目录行与正文重名时丢弃目录命中`() {
        // 书前目录: 每个章节名先在目录出现一遍,正文中再次出现
        val toc = (1..5).joinToString("\n") { "第${it}章 标题$it" }
        val body = (1..5).joinToString("\n\n") { "第${it}章 标题$it\n这是第${it}章的正文内容,足够长。" }
        val text = "书名 作者\n\n目录：\n$toc\n\n$body"
        val chapters = ChapterSplitter.split(text, "书名")
        // 书名段 + 5 个正文章;目录行全部被丢弃,不产生空章节
        assertEquals(6, chapters.size)
        assertEquals("第1章 标题1", chapters[1].title)
        val ch1 = chapters[1].startChar.toInt()
        // 第一章锚点指向正文章(最后一次出现),不是目录行
        assertEquals("第1章 标题1", text.substring(ch1, ch1 + 7))
    }

    @Test
    fun `无目录标记不过滤`() {
        // 无"目录"关键字: 即使标题重复出现也保守保留(正文引用场景)
        val text = "第一章 起点\n正文一\n\n第二章 转折\n正文二\n\n第一章 起点\n引用重现\n\n第二章 转折\n引用重现"
        val chapters = ChapterSplitter.split(text, "书名")
        assertEquals(4, chapters.size)
    }

    @Test
    fun `目录标记与正文标记之间的命中丢弃`() {
        val text = "书名\n\n目录：\n第一章 A\n第二章 B\n第三章 C\n\n正文\n\n第一章 A\n内容A\n\n第二章 B\n内容B\n\n第三章 C\n内容C"
        val chapters = ChapterSplitter.split(text, "书名")
        assertEquals(4, chapters.size)   // 书名段 + 三章
        assertEquals("第一章 A", chapters[1].title)
    }
}
