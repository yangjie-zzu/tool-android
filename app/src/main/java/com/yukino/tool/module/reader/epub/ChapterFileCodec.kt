package com.yukino.tool.module.reader.epub

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.yukino.tool.module.reader.common.ParaKind
import com.yukino.tool.module.reader.common.Paragraph
import com.yukino.tool.module.reader.common.Run

// 章文件编解码(二期 JSON 格式):
//   JSON 数组,每元素 { t: 投影文本, r: [s,e,style, s,e,style...] 扁平三元组, img: 图片相对路径 }。
//   图片段落 t 恒为 IMAGE_PLACEHOLDER 单字符,img 存相对解压根的路径(加载时转绝对)。
// 兼容: 一期老章文件是纯文本(段落 = split('\n')),读取先试 JSON,失败按纯文本回退——
//   老书无缝可读(无样式无图片),不做强制重初始化(列表前缀会改投影导致进度漂移)
object ChapterFileCodec {

    @Serializable
    private class ParagraphDto(val t: String, val r: List<Int> = emptyList(), val img: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun write(file: File, paragraphs: List<Paragraph>) {
        val dtos = paragraphs.map { p ->
            val r = ArrayList<Int>(p.runs.size * 3)
            for (run in p.runs) { r += run.start; r += run.end; r += run.style }
            ParagraphDto(t = p.text, r = r, img = if (p.isImage) p.imageRef else null)
        }
        file.writeText(json.encodeToString(ListSerializer(ParagraphDto.serializer()), dtos))
    }

    fun read(file: File): List<Paragraph> {
        val text = file.readText()
        val parsed = runCatching {
            json.decodeFromString(ListSerializer(ParagraphDto.serializer()), text)
        }.getOrNull()
        if (parsed != null) {
            return parsed.map { d ->
                val runs = ArrayList<Run>(d.r.size / 3)
                var i = 0
                while (i + 2 < d.r.size) {
                    runs += Run(d.r[i], d.r[i + 1], d.r[i + 2])
                    i += 3
                }
                if (d.img != null) {
                    Paragraph(d.t, emptyList(), ParaKind.IMAGE, d.img)
                } else {
                    Paragraph(d.t, runs.filter { it.end > it.start })
                }
            }
        }
        // 一期纯文本回退
        return text.split('\n').map { Paragraph(it) }
    }

    // 老格式检测(升级触发依据): JSON 章文件以 '[' 开头;一期纯文本章以正文文本开头。
    // 文件不可读时按老格式(调用方 ensureReady 已保证存在性)
    fun isLegacyFormat(file: File): Boolean = runCatching {
        file.inputStream().use { stream -> stream.read() != '['.code }
    }.getOrDefault(true)
}
