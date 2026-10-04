package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.BoxStyle
import com.yukino.tool.module.reader.common.BookPager
import com.yukino.tool.module.reader.common.ChapterLines
import com.yukino.tool.module.reader.common.LineKind
import com.yukino.tool.module.reader.common.ParaKind
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.common.ResolvedTypography
import com.yukino.tool.module.reader.common.TextLine
import com.yukino.tool.module.reader.epub.EpubImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// 装饰章(章首段带装饰盒)分页语义: 整章一页不跨页——页面按快照位图整体呈现。
// 纯 JVM(行模型 + paginate 纯函数),不依赖 Android 运行时
class DecorChapterPageTest {

    private fun typo() = ResolvedTypography(
        fontPx = 19f, lineExtraPx = 5f, paraExtraPx = 19f, indentPx = 38f,
        marginPx = 40, textWidth = 600, textHeight = 800,
        fgColor = 0xFF1A1A1A.toInt(), bgColor = 0xFFF6F1E7.toInt(), justify = true
    )

    // N 行正文行模型(每行 pitch 50),段落区间与行数对齐
    private fun chapterLines(para: Paragraph, nLines: Int): ChapterLines {
        val lines = (0 until nLines).map { i ->
            TextLine(i * 2, i * 2 + 1, LineKind.BODY, i == 0, 50, if (i == 0) 8 else 0, 10)
        }
        return ChapterLines("题\n\n正文", 4, 0L, lines, listOf(para), listOf(0 until 2))
    }

    @Test
    fun `装饰章整章一页不跨页`() {
        val deco = Paragraph("标题段", boxStyle = BoxStyle(bg = 0xFFFFFFFF))
        val cl = chapterLines(deco, nLines = 40)   // 40×50=2000,远超版心高 800
        val windows = BookPager.paginate(cl, typo())
        assertEquals(1, windows.size)
        assertEquals(0, windows[0].startLine)
        assertEquals(40, windows[0].endLineExclusive)
        assertTrue(BookPager.isDecorative(cl))
    }

    @Test
    fun `普通章照常切页`() {
        val normal = Paragraph("普通段")
        val cl = chapterLines(normal, nLines = 40)
        val windows = BookPager.paginate(cl, typo())
        assertTrue(windows.size > 1)
        assertTrue(!BookPager.isDecorative(cl))
    }

    // ---- 封面文档去重(与元数据封面同一资源的纯图文档跳过建章) ----

    private fun imgPara(ref: String) =
        Paragraph("", kind = ParaKind.IMAGE, imageRef = ref)

    @Test
    fun `纯图片文档且与元数据封面同一文件判定为封面文档`() {
        val root = Files.createTempDirectory("epubroot").toFile()
        val cover = File(root, "OEBPS/Images/cover.jpg")
        val doc = File(root, "OEBPS/Text/cover.xhtml")
        assertTrue(EpubImporter.isCoverDoc(listOf(imgPara("OEBPS/Images/cover.jpg")), cover, root, doc))
        // 相对文档目录的旧式相对路径同样命中(两种基准兼容)
        assertTrue(EpubImporter.isCoverDoc(listOf(imgPara("../Images/cover.jpg")), cover, root, doc))
    }

    @Test
    fun `非封面文档不误判`() {
        val root = Files.createTempDirectory("epubroot").toFile()
        val cover = File(root, "OEBPS/Images/cover.jpg")
        val doc = File(root, "OEBPS/Text/cover.xhtml")
        val other = File(root, "OEBPS/Images/other.jpg")
        // 含文字段(非纯图)
        assertFalse(EpubImporter.isCoverDoc(listOf(imgPara("OEBPS/Images/cover.jpg"), Paragraph("前言")), cover, root, doc))
        // 图片不是封面文件(如目录插画/正文插图页)
        assertFalse(EpubImporter.isCoverDoc(listOf(imgPara("OEBPS/Images/A0002.jpg")), cover, root, doc))
        // 元数据封面未探测到 → 不判定,保留文档
        assertFalse(EpubImporter.isCoverDoc(listOf(imgPara("OEBPS/Images/cover.jpg")), null, root, doc))
        assertFalse(cover.exists() || doc.exists())   // 判定为纯函数,不触碰文件系统
    }

    @Test
    fun `内容等价的封面副本命中_升级搬运场景`() {
        val root = Files.createTempDirectory("epubroot").toFile()
        val imgDir = File(root, "OEBPS/Images"); imgDir.mkdirs()
        val cover = File(root, "cover.jpg")   // 升级搬运后的书目录根副本
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        cover.writeBytes(bytes)
        File(imgDir, "cover.jpg").writeBytes(bytes)   // 解压目录内原图(同内容不同路径)
        val doc = File(root, "OEBPS/Text/cover.xhtml")
        assertTrue(EpubImporter.isCoverDoc(listOf(imgPara("OEBPS/Images/cover.jpg")), cover, root, doc))
        // 内容不同的"副本"不命中
        File(imgDir, "cover.jpg").writeBytes(ByteArray(2048) { (it % 251).toByte() } + byteArrayOf(1))
        assertFalse(EpubImporter.isCoverDoc(listOf(imgPara("OEBPS/Images/cover.jpg")), cover, root, doc))
    }
}
