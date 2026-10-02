package com.yukino.tool.module.reader.epub

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.yukino.tool.module.reader.common.NoteAnchor
import com.yukino.tool.module.reader.common.ParaKind
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.common.Run

// 章文件编解码:
//   三期对象格式 { p: [段落], notes: {noteId: 脚注文本} };
//   段落 { t: 投影文本, r: [s,e,style...] 扁平三元组, img: 图片相对路径,
//          a: 段锚点(XHTML id), n: [脚注角标区间] }。
//   图片段落 t 恒为 IMAGE_PLACEHOLDER 单字符,img 存相对解压根的路径(加载时转绝对)。
// 兼容读三种历史格式: 二期 JSON 数组(段落无锚点/脚注)→ 一期纯文本(split('\n'))——
//   老书无缝可读,打开时由 EpubImporter 自动升级
object ChapterFileCodec {

    @Serializable
    private data class NoteAnchorDto(val s: Int, val e: Int, val id: String)

    @Serializable
    private data class ParagraphDto(
        val t: String,
        val r: List<Int> = emptyList(),
        val img: String? = null,
        val a: String? = null,
        val n: List<NoteAnchorDto> = emptyList(),
        val al: Int? = null,        // 四期: 段级对齐(1=center 2=right)
        val ind: Float? = null,     // 四期: 书内首行缩进(em;null=跟随全局)
        val mt: Float? = null,      // 四期: 块级 margin-top(em)
        val mb: Float? = null,      // 四期: 块级 margin-bottom(em)
        val h: Int? = null          // 四期: 标题级别 1..6(h2 拆章依据)
    )

    @Serializable
    private data class ChapterDto(
        val p: List<ParagraphDto>,
        val notes: Map<String, String> = emptyMap(),
        val v: Int = 0   // 格式版本: 4 = 四期(段级排版属性/heading,拆章依据);旧文件缺省 0
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun write(file: File, paragraphs: List<Paragraph>, footnotes: Map<String, String> = emptyMap()) {
        val dtos = paragraphs.map { p ->
            val r = ArrayList<Int>(p.runs.size * 3)
            for (run in p.runs) { r += run.start; r += run.end; r += run.style }
            ParagraphDto(
                t = p.text, r = r,
                img = if (p.isImage) p.imageRef else null,
                a = p.anchor,
                n = p.notes.map { NoteAnchorDto(it.start, it.end, it.noteId) },
                al = if (p.align != 0) p.align else null,
                ind = p.indentEm,
                mt = p.spaceAboveEm,
                mb = p.spaceBelowEm,
                h = if (p.heading != 0) p.heading else null
            )
        }
        val dto = ChapterDto(p = dtos, notes = footnotes, v = FORMAT_VERSION)
        file.writeText(json.encodeToString(ChapterDto.serializer(), dto))
    }

    // 四期格式版本: 段级排版属性 + heading(h2 拆章)。低版本文件打开时自动升级重提取
    const val FORMAT_VERSION = 4

    fun read(file: File): Pair<List<Paragraph>, Map<String, String>> {
        val text = file.readText()
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{")) {
            // 三/四期对象格式
            runCatching {
                val dto = json.decodeFromString(ChapterDto.serializer(), text)
                return dto.p.map { it.toParagraph() } to dto.notes
            }
        }
        if (trimmed.startsWith("[")) {
            // 二期数组格式
            runCatching {
                val parsed = json.decodeFromString(ListSerializer(ParagraphDto.serializer()), text)
                return parsed.map { it.toParagraph() } to emptyMap()
            }
        }
        // 一期纯文本回退
        return text.split('\n').map { Paragraph(it) } to emptyMap()
    }

    private fun ParagraphDto.toParagraph(): Paragraph {
        val runs = ArrayList<Run>(r.size / 3)
        var i = 0
        while (i + 2 < r.size) {
            runs += Run(r[i], r[i + 1], r[i + 2])
            i += 3
        }
        if (img != null) {
            return Paragraph(
                t, emptyList(), ParaKind.IMAGE, img, a,
                n.map { NoteAnchor(it.s, it.e, it.id) },
                align = al ?: 0, spaceAboveEm = mt, spaceBelowEm = mb
            )
        }
        return Paragraph(
            t, runs.filter { it.end > it.start },
            anchor = a,
            notes = n.map { NoteAnchor(it.s, it.e, it.id) },
            align = al ?: 0,
            indentEm = ind,
            spaceAboveEm = mt,
            spaceBelowEm = mb,
            heading = h ?: 0
        )
    }

    // 升级检测(打开书时触发重新提取): 仅四期对象格式('{'开头且 v=FORMAT_VERSION)豁免——
    // 二期数组('[')/一期纯文本/三期对象(v=0,缺锚点/脚注/排版属性/heading)都需走升级。
    // 文件不可读按需升级(调用方 ensureReady 已保证存在性)
    fun needsUpgrade(file: File): Boolean = runCatching {
        val text = file.readText()
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return@runCatching true
        val dto = json.decodeFromString(ChapterDto.serializer(), text)
        dto.v < FORMAT_VERSION
    }.getOrDefault(true)
}
