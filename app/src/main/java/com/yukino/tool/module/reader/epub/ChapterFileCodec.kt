package com.yukino.tool.module.reader.epub

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.yukino.tool.module.reader.common.NoteAnchor
import com.yukino.tool.module.reader.common.InlineImg
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
    private data class InlineImgDto(val s: Int, val ref: String)

    @Serializable
    private data class CssLenDto(val v: Float, val pct: Boolean = false)

    @Serializable
    private data class EdgeDto(val w: Float = 0f, val st: Int = 0, val c: Long = 0xFF000000)

    @Serializable
    private data class BoxDto(
        val bg: Long? = null,
        val bgImg: String? = null,
        val rad: CssLenDto? = null,
        val shadow: Boolean = false,
        val pt: Float = 0f, val pb: Float = 0f, val pl: Float = 0f, val pr: Float = 0f,
        val edges: List<EdgeDto> = emptyList()
    )

    @Serializable
    private data class ParagraphDto(
        val t: String,
        val r: List<Int> = emptyList(),
        val img: String? = null,
        val a: String? = null,
        val n: List<NoteAnchorDto> = emptyList(),
        val al: Int? = null,        // 四期: 段级对齐(1=center 2=right;七期 3=left 4=justify)
        val ind: Float? = null,     // 四期: 书内首行缩进(em;null=跟随全局)
        val mt: CssLenDto? = null,  // 块级 margin-top(七期起 CssLen: em/px/%)
        val mb: CssLenDto? = null,  // 块级 margin-bottom
        val h: Int? = null,         // 四期: 标题级别 1..6(h2 拆章依据)
        val ii: List<InlineImgDto> = emptyList(),  // 五期: 行内图片(段内偏移+相对路径)
        val ml: CssLenDto? = null,  // 七期: 整段左缩进(盒 padding 折算并入)
        val mr: CssLenDto? = null,  // 七期: 整段右缩进/定宽推出的右留白
        val w: CssLenDto? = null,   // 七期: 定宽
        val wc: Boolean = false,    // 七期: 定宽且左右 margin auto(排版期居中)
        val lh: Float? = null,      // 七期: 段级行距倍率
        val b: Int? = null          // 七期: 盒样式下标(boxes 表)
    )

    @Serializable
    private data class ChapterDto(
        val p: List<ParagraphDto>,
        val notes: Map<String, String> = emptyMap(),
        val boxes: Map<Int, BoxDto> = emptyMap(),  // 七期: 盒样式表(段落 b 下标引用)
        val v: Int = 0   // 格式版本: 8 = 七期(盒样式/左右缩进/行距/显式对齐);旧文件缺省 0
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun write(file: File, paragraphs: List<Paragraph>, footnotes: Map<String, String> = emptyMap()) {
        // 盒样式表: 按 BoxStyle 去重(相邻段落共享同一实例,序列化后经 equals 聚合还原同组)
        val boxIndex = LinkedHashMap<com.yukino.tool.module.reader.common.BoxStyle, Int>()
        val boxes = ArrayList<BoxDto>()
        fun boxIdOf(bs: com.yukino.tool.module.reader.common.BoxStyle): Int =
            boxIndex.getOrPut(bs) {
                val id = boxes.size
                boxes += BoxDto(
                    bg = bs.bg, bgImg = bs.bgImage,
                    rad = bs.radius?.let { CssLenDto(it.v, it.pct) },
                    shadow = bs.shadow,
                    pt = bs.padTopEm, pb = bs.padBottomEm, pl = bs.padLeftEm, pr = bs.padRightEm,
                    edges = bs.edges.map { EdgeDto(it.widthEm, it.style, it.color) }
                )
                id
            }
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
                mt = p.spaceAboveEm?.let { CssLenDto(it.v, it.pct) },
                mb = p.spaceBelowEm?.let { CssLenDto(it.v, it.pct) },
                h = if (p.heading != 0) p.heading else null,
                ii = p.inlineImages.map { InlineImgDto(it.start, it.ref) },
                ml = p.marginLeftEm?.let { CssLenDto(it.v, it.pct) },
                mr = p.marginRightEm?.let { CssLenDto(it.v, it.pct) },
                w = p.widthEm?.let { CssLenDto(it.v, it.pct) },
                wc = p.widthCenter,
                lh = p.lineSpacingMult,
                b = p.boxStyle?.let { boxIdOf(it) }
            )
        }
        val dto = ChapterDto(
            p = dtos, notes = footnotes,
            boxes = boxes.indices.associate { it to boxes[it] },
            v = FORMAT_VERSION
        )
        file.writeText(json.encodeToString(ChapterDto.serializer(), dto))
    }

    // 七期批次二格式版本: 盒样式(底色/边框/圆角/阴影/背景图)/左右缩进/定宽/段级行距/
    // 显式 left/justify 对齐(解析与排版期能力,v7 书缺这些识别结果)。
    // 低版本文件打开时自动升级重提取
    const val FORMAT_VERSION = 8

    private fun BoxDto.toBoxStyle() = com.yukino.tool.module.reader.common.BoxStyle(
        bg = bg, bgImage = bgImg,
        radius = rad?.let { com.yukino.tool.module.reader.common.CssLen(it.v, it.pct) },
        shadow = shadow,
        padTopEm = pt, padBottomEm = pb, padLeftEm = pl, padRightEm = pr,
        edges = edges.map { com.yukino.tool.module.reader.common.EdgeStyle(it.w, it.st, it.c) }
    )

    private fun CssLenDto.toCssLen() = com.yukino.tool.module.reader.common.CssLen(v, pct)

    fun read(file: File): Pair<List<Paragraph>, Map<String, String>> {
        val text = file.readText()
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{")) {
            // 三/四期对象格式
            runCatching {
                val dto = json.decodeFromString(ChapterDto.serializer(), text)
                val boxStyles = dto.boxes.mapValues { it.value.toBoxStyle() }
                return dto.p.map { it.toParagraph(boxStyles) } to dto.notes
            }
        }
        if (trimmed.startsWith("[")) {
            // 二期数组格式
            runCatching {
                val parsed = json.decodeFromString(ListSerializer(ParagraphDto.serializer()), text)
                return parsed.map { it.toParagraph(emptyMap()) } to emptyMap()
            }
        }
        // 一期纯文本回退
        return text.split('\n').map { Paragraph(it) } to emptyMap()
    }

    private fun ParagraphDto.toParagraph(boxStyles: Map<Int, com.yukino.tool.module.reader.common.BoxStyle>): Paragraph {
        val runs = ArrayList<Run>(r.size / 3)
        var i = 0
        while (i + 2 < r.size) {
            runs += Run(r[i], r[i + 1], r[i + 2])
            i += 3
        }
        val inlines = ii.map { InlineImg(it.s, it.ref) }
        if (img != null) {
            return Paragraph(
                t, emptyList(), ParaKind.IMAGE, img, a,
                n.map { NoteAnchor(it.s, it.e, it.id) },
                align = al ?: 0, spaceAboveEm = mt?.toCssLen(), spaceBelowEm = mb?.toCssLen(),
                marginLeftEm = ml?.toCssLen(), marginRightEm = mr?.toCssLen(),
                widthEm = w?.toCssLen(), widthCenter = wc, lineSpacingMult = lh,
                boxStyle = b?.let { boxStyles[it] }
            )
        }
        return Paragraph(
            t, runs.filter { it.end > it.start },
            anchor = a,
            notes = n.map { NoteAnchor(it.s, it.e, it.id) },
            align = al ?: 0,
            indentEm = ind,
            spaceAboveEm = mt?.toCssLen(),
            spaceBelowEm = mb?.toCssLen(),
            heading = h ?: 0,
            inlineImages = inlines,
            marginLeftEm = ml?.toCssLen(),
            marginRightEm = mr?.toCssLen(),
            widthEm = w?.toCssLen(),
            widthCenter = wc,
            lineSpacingMult = lh,
            boxStyle = b?.let { boxStyles[it] }
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
