package com.yukino.tool.module.reader.pdf

import org.junit.Test
import java.io.ByteArrayOutputStream

// PdfOutlineParser JVM 单测: 构造最小带书签 PDF(3 页, 顶级/子级书签), 验证书签树解析
class PdfOutlineParserTest {

    private fun buildPdf(): ByteArray {
        val objs = mutableMapOf<Int, ByteArray>()
        objs[1] = "<< /Type /Catalog /Pages 2 0 R /Outlines 5 0 R >>".toByteArray()
        objs[2] = "<< /Type /Pages /Kids [3 0 R 12 0 R 13 0 R] /Count 3 >>".toByteArray()
        fun pageObj(num: Int, contentNum: Int) =
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents $contentNum 0 R >>".toByteArray()
        objs[3] = pageObj(3, 4)
        fun content(text: String): ByteArray {
            val s = "BT /F1 24 Tf 72 700 Td ($text) Tj ET".toByteArray()
            return "<< /Length ${s.size} >>\nstream\n".toByteArray() + s + "\nendstream".toByteArray()
        }
        objs[4] = content("Page 1 - Chapter One")
        objs[5] = "<< /Type /Outlines /First 6 0 R /Last 14 0 R /Count 3 >>".toByteArray()
        // Chapter One 带 /First 7 子书签
        objs[6] = "<< /Title (Chapter One) /Parent 5 0 R /First 7 0 R /Last 7 0 R /Count 1 /Dest [3 0 R /XYZ null null null] /Next 14 0 R >>".toByteArray()
        objs[7] = "<< /Title (Section 1.1) /Parent 6 0 R /Dest [12 0 R /Fit] >>".toByteArray()
        objs[12] = pageObj(12, 15)
        objs[13] = pageObj(13, 16)
        objs[14] = "<< /Title (Chapter Two) /Parent 5 0 R /Prev 6 0 R /Dest [13 0 R /XYZ null null null] >>".toByteArray()
        objs[15] = content("Page 2 - Section 1.1")
        objs[16] = content("Page 3 - Chapter Two")

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val offsets = sortedMapOf<Int, Int>()
        for (num in objs.keys.sorted()) {
            offsets[num] = out.size()
            out.write("$num 0 obj\n".toByteArray())
            out.write(objs[num]!!)
            out.write("\nendobj\n".toByteArray())
        }
        val xref = out.size()
        val maxn = objs.keys.max() + 1
        out.write("xref\n0 $maxn\n".toByteArray())
        out.write("0000000000 65535 f \n".toByteArray())
        for (i in 1 until maxn) {
            val off = offsets[i]
            out.write(
                if (off == null) "0000000000 65535 f \n".toByteArray()
                else "%010d 00000 n \n".format(off).toByteArray()
            )
        }
        out.write("trailer\n<< /Size $maxn /Root 1 0 R >>\nstartxref\n".toByteArray())
        out.write("$xref\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun parseOutlineTree() {
        val items = PdfOutlineParser.parse(buildPdf(), 3)
        println("items=$items")
        check(items.size == 3) { "期望 3 条书签, 实得 ${items.size}: $items" }
        check(items[0].title == "Chapter One" && items[0].page == 0 && items[0].level == 0)
        check(items[1].title == "Section 1.1" && items[1].page == 1 && items[1].level == 1)
        check(items[2].title == "Chapter Two" && items[2].page == 2 && items[2].level == 0)
    }
}
