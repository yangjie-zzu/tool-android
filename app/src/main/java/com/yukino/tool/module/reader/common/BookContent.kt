package com.yukino.tool.module.reader.common
// ==================== 内容模型与内容源抽象 ====================
// 导入侧(TxtImporter / EpubImporter 各自主流程)的产物收敛到这里:
// 排版层(BookPager)与渲染层(ReaderPageView)只认识 BookContent/ChapterDocument,
// 不认识具体格式——两种格式的主流程在公共层相遇,互不感知。

// 内联样式位标记(Run.style)。富文本只影响绘制,不参与偏移/进度/选择等一切投影机制
object RunStyle {
    const val BOLD = 1
    const val ITALIC = 2
    const val UNDERLINE = 4
    const val STRIKE = 8
    const val SUP = 16      // 上标: 字号缩小 + 基线上移
    const val SUB = 32      // 下标: 字号缩小 + 基线下移
}

// 段内 [start, end) 区间(纯文本投影坐标)的样式;style 为 RunStyle 位或。
// 段内排序不重叠、相邻同样式已合并(解析侧保证)
data class Run(val start: Int, val end: Int, val style: Int)

enum class ParaKind { TEXT, IMAGE }

// 段内脚注角标区间(纯文本投影坐标): 角标文本保留在投影中(如 "[1]"),
// noteId 指向章级 footnotes 表(不进正文阅读流)
class NoteAnchor(val start: Int, val end: Int, val noteId: String)

// 段落级书内排版(四期 CSS 子集;来源 = style 属性 + 文档 class 样式表,解析侧提取):
// align 0=默认(跟随全局) 1=居中 2=右对齐;indentEm 非空覆盖全局首行缩进(null=跟随全局);
// spaceAboveEm/spaceBelowEm 书内块级 margin(em),排版时按"段距跟随书内"开关取舍;
// heading = 1..6 表示该段源自 hn 标题(h2 拆章依据),0 = 普通段落
class Paragraph(
    val text: String,
    val runs: List<Run> = emptyList(),
    val kind: ParaKind = ParaKind.TEXT,
    val imageRef: String? = null,
    val anchor: String? = null,
    val notes: List<NoteAnchor> = emptyList(),
    val align: Int = 0,
    val indentEm: Float? = null,
    val spaceAboveEm: Float? = null,
    val spaceBelowEm: Float? = null,
    val heading: Int = 0
) {
    val isImage: Boolean get() = kind == ParaKind.IMAGE
}

// 章文档: 标题 + 正文段落序列。bodyText 是段落的纯文本投影(段间一个 \n),
// 分页/进度/选区锚定的全书偏移全部定义在投影上;
// footnotes = 本章脚注内容表(noteId → 纯文本,弹层展示),不参与排版与偏移
class ChapterDocument(
    val title: String,
    val paragraphs: List<Paragraph>,
    val footnotes: Map<String, String> = emptyMap()
) {
    val bodyText: String get() = paragraphs.joinToString("\n") { it.text }
}

// 内容源: 排版层眼中的"一本书"。章节区间约定:
//   章正文在全书偏移轴上占 [chapterStart, chapterStart + bodyText.length),
//   章与章之间隔一个虚拟换行偏移(txt 例外——源文本天然连续,区间无缝拼接);
//   textAt 按 chapterDoc 的纯文本投影裁切,供复制/分享等取选中文本
interface BookContent {
    val bookTitle: String
    val totalChars: Long
    val chapterCount: Int                      // 正文章数(≥1;无章节书 = 整本单章)

    fun chapterTitle(index: Int): String
    fun chapterStart(index: Int): Long         // 章首全书偏移(含尚未剥掉的正文自带标题)
    fun chapterDoc(index: Int): ChapterDocument

    // 全书偏移区间 [start, end) 的选中文本(章界处补换行)
    fun textAt(startGlobal: Long, endGlobal: Long): String

    // ---- 图片支持(二期;纯文本内容源用默认空实现) ----

    // 图片像素尺寸(imageRef 为图片文件绝对路径;不可解码返回 null)。
    // 排版侧按版心宽等比换算占位高度
    fun imageBounds(imageRef: String): android.graphics.Rect? = null

    // 封面文件绝对路径(null/文件不存在 = 无封面)。阅读页封面页与书架缩略图共用
    val coverPath: String? get() = null

    // ---- 锚点与脚注(三期;TXT 用默认空实现) ----

    // 章内锚点(anchorId,目录 fragment)的章内投影偏移;找不到返回 null(调用方落章首)
    fun anchorOffset(chapterIndex: Int, anchorId: String): Long? = null

    // 全书偏移处的脚注角标 → (noteId, 脚注内容);非角标位置返回 null。
    // 供点击命中: tap → 字符全书偏移 → 本查询
    fun footnoteAt(globalOffset: Long): Pair<String, String>? = null
}

// TXT 内容源: 全书单文本流 + 章节偏移表切片。无章节书归一化为"整本单章"
class TxtBookContent(private val book: ReaderBook, private val fullText: String) : BookContent {

    private val chapters = book.chapters.ifEmpty { listOf(ChapterIndex(book.title, 0L)) }

    override val bookTitle: String get() = book.title
    override val totalChars: Long get() = book.totalChars
    override val chapterCount: Int get() = chapters.size

    override fun chapterTitle(index: Int): String = chapters[index].title

    override fun chapterStart(index: Int): Long = chapters[index].startChar

    override fun chapterDoc(index: Int): ChapterDocument {
        val title = chapters[index].title
        val start = chapters[index].startChar.toInt().coerceIn(0, fullText.length)
        val end = if (index + 1 < chapters.size) {
            chapters[index + 1].startChar.toInt().coerceIn(start, fullText.length)
        } else fullText.length
        return ChapterDocument(title, fullText.substring(start, end).split('\n').map { Paragraph(it) })
    }

    // 全书文本裁切,章界处补换行(章首剥掉的原标题行不落在任何正文页上,
    // 选区永不覆盖它,子串天然干净;相邻章内容在源文本中是连续的,补 \n 还原段落分隔)
    override fun textAt(startGlobal: Long, endGlobal: Long): String {
        val s = startGlobal.toInt().coerceIn(0, fullText.length)
        val e = endGlobal.toInt().coerceIn(s, fullText.length)
        if (e <= s) return ""
        val sb = StringBuilder(e - s + 8)
        var prev = s
        for (ch in book.chapters) {
            val c = ch.startChar.toInt()
            if (c <= prev) continue
            if (c >= e) break
            sb.append(fullText, prev, c).append('\n')
            prev = c
        }
        sb.append(fullText, prev, e)
        return sb.toString()
    }
}

// 导入结果: 两条导入主流程共用(原 TxtImporter.ImportResult 上提)
sealed interface ImportResult {
    data class Success(val book: ReaderBook) : ImportResult
    data class FolderImported(val groupCount: Int, val bookCount: Int) : ImportResult
    data class Failed(val reason: String) : ImportResult
}
