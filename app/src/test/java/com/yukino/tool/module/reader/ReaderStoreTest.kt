package com.yukino.tool.module.reader

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

// 持久化序列化往返: 字段缺失时走默认值,防止以后加字段炸老数据
class ReaderStoreTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `book序列化往返恒等`() {
        val book = ReaderBook(
            id = "id-1",
            title = "测试书",
            sourceUri = "content://x/y",
            cachePath = "/cache/x.txt",
            encoding = "GB18030",
            totalChars = 123456,
            chapters = listOf(ChapterIndex("第一章", 0), ChapterIndex("第二章", 100)),
            addedAt = 111L,
            lastReadAt = 222L,
            progress = Progress(globalCharOffset = 155, percent = 0.5)
        )
        val restored = json.decodeFromString<ReaderBook>(json.encodeToString(book))
        assertEquals(book, restored)
    }

    @Test
    fun `老版本json缺progress字段走默认值`() {
        val old = """
            {"id":"a","title":"t","sourceUri":"u","cachePath":"c","encoding":"UTF-8",
             "totalChars":10,"chapters":[],"addedAt":1,"lastReadAt":2}
        """.trimIndent()
        val book = json.decodeFromString<ReaderBook>(old)
        assertEquals(Progress(), book.progress)
    }

    @Test
    fun `settings序列化往返与默认值`() {
        val restored = json.decodeFromString<ReaderSettings>(json.encodeToString(ReaderSettings()))
        assertEquals(ReaderSettings(), restored)
        val custom = ReaderSettings(theme = ReaderTheme.NIGHT, fontSizeDp = 24f, justify = false)
        assertEquals(custom, json.decodeFromString<ReaderSettings>(json.encodeToString(custom)))
    }

    @Test
    fun `未知字段不报错`() {
        val s = json.decodeFromString<ReaderSettings>("""{"theme":"SEPIA","futureField":1}""")
        assertEquals(ReaderTheme.SEPIA, s.theme)
    }
}
