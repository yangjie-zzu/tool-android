package com.yukino.tool.module.reader

import kotlinx.serialization.Serializable

// 章节: 标题 + 全书字符起始偏移
@Serializable
data class ChapterIndex(val title: String, val startChar: Long)

// 进度: 全书字符偏移(换字号重分页后位置不漂移), percent 仅展示用。
// 旧版本(chapterIndex+charOffset)数据不做迁移,落到默认值回到书首
@Serializable
data class Progress(
    val globalCharOffset: Long = 0,
    val percent: Double = 0.0
)

@Serializable
data class ReaderBook(
    val id: String,
    val title: String,
    val sourceUri: String,   // SAF uri(已 takePersistableUriPermission),缓存丢失时重新转存
    val cachePath: String,   // 导入时转存的 UTF-8 文本
    val encoding: String,    // 检测出的原始编码,仅展示
    val totalChars: Long,    // 全书字符数(算百分比)
    val chapters: List<ChapterIndex>,
    val addedAt: Long,
    val lastReadAt: Long,
    val progress: Progress = Progress(),
    val fileSize: Long = 0L    // 缓存文件字节数(书架展示);旧数据缺省 0 不显示
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
    val keepScreenOn: Boolean = true
) {
    val effectiveBg: Long get() = if (theme == ReaderTheme.CUSTOM) customBg ?: theme.colors.first else theme.colors.first
    val effectiveFg: Long get() = if (theme == ReaderTheme.CUSTOM) customFg ?: theme.colors.second else theme.colors.second
}
