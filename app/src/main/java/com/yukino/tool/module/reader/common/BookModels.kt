package com.yukino.tool.module.reader.common
import kotlinx.serialization.Serializable

// 书籍格式标识(ReaderBook.format 存储值)
object BookFormat {
    const val TXT = "txt"
    const val EPUB = "epub"
}

// 章节: 标题 + 全书字符起始偏移。anchorId = 目录条目指向的文档内锚点
// (EPUB 目录 fragment,点击目录落该锚点所在段落而非章首;旧数据/无锚点为 null);
// level = 目录层级(0 = 章/spine 文档, 1 = 文档内 h2 拆出的小节,目录缩进展示)
@Serializable
data class ChapterIndex(
    val title: String,
    val startChar: Long,
    val anchorId: String? = null,
    val level: Int = 0
)

// 进度: 全书字符偏移(换字号重分页后位置不漂移), percent 仅展示用。
// 旧版本(chapterIndex+charOffset)数据不做迁移,落到默认值回到书首
@Serializable
data class Progress(
    val globalCharOffset: Long = 0,
    val percent: Double = 0.0
)

// 分组: 可嵌套(parentId 指向另一组, NULL=顶层组)。存 reader_group 表。
// sourceUri: 导入文件夹成组时记录的文件夹 tree uri(重复导入同一文件夹据此拒绝)
data class ReaderGroup(
    val id: String,
    val name: String,
    val parentId: String?,   // NULL = 顶层组
    val addedAt: Long,
    val sourceUri: String? = null   // 手动创建的组为 NULL
)

@Serializable
data class ReaderBook(
    val id: String,
    val title: String,
    val sourceUri: String,   // SAF uri(已 takePersistableUriPermission),缓存丢失时重新转存
    val cachePath: String,   // txt: 转存的 UTF-8 文本;epub: 章节文件目录(懒初始化后有效)
    val encoding: String,    // 检测出的原始编码,仅展示
    val totalChars: Long,    // 全书字符数(算百分比)
    val chapters: List<ChapterIndex>,
    val addedAt: Long,
    val lastReadAt: Long,
    val progress: Progress = Progress(),
    val fileSize: Long = 0L,  // 源文件字节数(书架展示,登记时即可知)
    val format: String = BookFormat.TXT,  // txt/epub;旧数据缺省 txt
    val author: String? = null,           // epub 元数据,首次打开后回填
    val coverPath: String? = null,        // 封面图路径(二期启用);一期恒空
    val groupId: String? = null,          // 所属分组;NULL = 未分组
    val ready: Boolean = true             // false = 未初始化(内容未落盘/章节未解析,首次打开时补齐)
)

enum class ReaderTheme { PAPER, SEPIA, GREEN, NIGHT, CUSTOM }

// 主题预设 (背景, 前景)
val ReaderTheme.colors: Pair<Long, Long>
    get() = when (this) {
        ReaderTheme.PAPER -> 0xFFF6F1E7 to 0xFF1A1A1A
        ReaderTheme.SEPIA -> 0xFFE8DCC0 to 0xFF4A3B28
        ReaderTheme.GREEN -> 0xFFC7EDCC to 0xFF2C3A2E
        ReaderTheme.NIGHT -> 0xFF15171A to 0xFF9DA5AE
        ReaderTheme.CUSTOM -> 0xFFF6F1E7 to 0xFF1A1A1A // 未选自定义色时的兜底
    }

// 阅读设置: 以 dp 存储(不用 sp,避免系统 fontScale 两侧不一致),Typography 唯一换算
@Serializable
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val customBg: Long? = null,        // theme == CUSTOM 时生效
    val customFg: Long? = null,
    val fontSizeDp: Float = 19f,       // 12..32,步进 1
    val lineSpacingPercent: Int = 180, // 120..240,步进 10
    val paragraphSpacingPercent: Int = 100, // 段距: 段落末行下方追加的空隙(字号%,50..300,步进 50)
    val marginDp: Int = 16,            // 8..32,步进 4
    val indent: Boolean = true,        // 首行缩进 2 字符
    val justify: Boolean = true,       // 两端对齐
    val keepScreenOn: Boolean = true,
    val bookSpacing: Boolean = true    // 段距跟随书内 CSS(四期): 关=忽略书内 margin 纯用全局段距
) {
    val effectiveBg: Long get() = if (theme == ReaderTheme.CUSTOM) customBg ?: theme.colors.first else theme.colors.first
    val effectiveFg: Long get() = if (theme == ReaderTheme.CUSTOM) customFg ?: theme.colors.second else theme.colors.second
}
