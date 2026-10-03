package com.yukino.tool.module.reader

import com.yukino.tool.module.reader.common.ChapterIndex
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.epub.ChapterFileCodec
import com.yukino.tool.module.reader.epub.HtmlTextExtractor
import org.jsoup.Jsoup
import java.io.File

fun main() {
    val root = File("tmp_epub_analysis")
    val opf = Jsoup.parse(File(root, "OEBPS/content.opf"), "UTF-8")
    val manifest = HashMap<String, String>()
    for (item in opf.select("manifest > item")) manifest[item.attr("id")] = item.attr("href")
    val spine = opf.select("spine > itemref").map { manifest[it.attr("idref")]!! }

    data class Section(val paras: List<Paragraph>, val h2Text: String?, val h2Anchor: String?, val first: Boolean)
    fun splitSections(paras: List<Paragraph>): List<Section> {
        val bounds = ArrayList<Int>()
        for ((i, p) in paras.withIndex()) if (p.heading == 2 && i > 0) bounds += i
        if (bounds.isEmpty()) return listOf(Section(paras, null, null, true))
        val raw = ArrayList<List<Paragraph>>(); var start = 0
        for (b in bounds) { raw += paras.subList(start, b); start = b }
        raw += paras.subList(start, paras.size)
        return raw.mapIndexed { k, list ->
            if (list.isEmpty()) null
            else if (k > 0 && list.first().heading == 2) Section(list, list.first().text, list.first().anchor, false)
            else Section(list, null, null, k == 0)
        }.filterNotNull()
    }

    val outDir = File(root, "gen/bookid01/chapters")
    outDir.deleteRecursively(); outDir.mkdirs()
    val chapters = ArrayList<ChapterIndex>(); var offset = 0L; var index = 0
    for (href in spine) {
        val f = File(root, "OEBPS/$href")
        if (!f.exists()) continue
        val docDir = f.parentFile!!.invariantSeparatorsPath.substring(root.invariantSeparatorsPath.length + 1)
        val extracted = HtmlTextExtractor.extract(f, docDir)
        val tocTitle = HtmlTextExtractor.firstHeading(f) ?: f.nameWithoutExtension
        val displayed = if (extracted.paragraphs.all { it.isImage }) "插图" else tocTitle
        for (sec in splitSections(extracted.paragraphs)) {
            ChapterFileCodec.write(File(outDir, "ch_%04d.txt".format(index)), sec.paras, extracted.footnotes, extracted.fonts)
            val bodyLen = sec.paras.sumOf { it.text.length.toLong() } + (sec.paras.size - 1)
            chapters += ChapterIndex((sec.h2Text ?: displayed).take(60), offset, sec.h2Anchor, if (sec.first) 0 else 1)
            offset += bodyLen + 1; index++
        }
    }
    println("章文件: $index")
}
