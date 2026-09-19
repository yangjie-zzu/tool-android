package com.yukino.tool.module.reader

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.Typeface

// 排版换算的唯一定义点: 设置(dp) → px。测量与绘制都必须从这里取值,禁止散落乘 density。
// density 做参数注入以便本地单测换算公式。
data class ResolvedTypography(
    val fontPx: Float,
    val lineExtraPx: Float,   // 期望行高 = 字号×行距%,StaticLayout 加在 ascent..descent 上的增量
    val paraExtraPx: Float,   // 段距: 段落末行下方追加的空隙(字号×段距%)
    val indentPx: Float,      // 首行缩进
    val marginPx: Int,
    val textWidth: Int,       // viewport 宽 - 左右边距
    val textHeight: Int,      // viewport 高 - 页眉区 - 页脚区
    val fgColor: Int,
    val bgColor: Int,
    val justify: Boolean
)

object Typography {

    // 断行/排版算法版本: 算法变化(如断行策略切换)时 +1,使旧分页缓存失效
    const val BREAK_STRATEGY_VERSION = 3

    // 页眉/页脚文字区高度(dp),悬浮于内容区上下留白内,不占版心
    const val TOP_GAP_DP: Int = 8
    const val PAGE_PADDING_DP: Int = 16
    const val FOOTER_GAP_DP: Int = 36   // 页脚保留区: 正文底与页脚文字的间距(24dp 时底部观感偏小,与顶部 24dp 不对称)

    const val FONT_MIN = 12f
    const val FONT_MAX = 32f
    const val SPACING_MIN = 120
    const val SPACING_MAX = 240
    const val MARGIN_MIN = 8
    const val MARGIN_MAX = 32

    fun resolve(density: Float, s: ReaderSettings, viewportWidth: Int, viewportHeight: Int): ResolvedTypography {
        val fontPx = s.fontSizeDp * density
        val indentPx = if (s.indent) 2f * fontPx else 0f
        // 版心精确等于可见内容区: 顶 pad(16dp) + 页眉间距(8dp),底部无预留——
        // 每页剩余的零头空白由 BookPager.materialize 二次构建分配到页首/段距
        val verticalChrome = ((PAGE_PADDING_DP + TOP_GAP_DP + FOOTER_GAP_DP) * density).toInt()
        return ResolvedTypography(
            fontPx = fontPx,
            lineExtraPx = fontPx * (s.lineSpacingPercent / 100f - 1f),
            paraExtraPx = fontPx * (s.paragraphSpacingPercent / 100f),
            indentPx = indentPx,
            marginPx = (s.marginDp * density).toInt(),
            textWidth = (viewportWidth - 2 * (s.marginDp * density).toInt()).coerceAtLeast(1),
            textHeight = (viewportHeight - verticalChrome).coerceAtLeast(fontPx.toInt() * 2),
            fgColor = s.effectiveFg.toInt(),
            bgColor = s.effectiveBg.toInt(),
            justify = s.justify
        )
    }

    // 构建 StaticLayout: 文本可为 Spanned(样式/缩进 span 由调用方——ChapterComposer——套好)。
    // 测量(整章)与渲染(单页)共用同一构建规则,保证断行一致
    fun buildLayout(text: CharSequence, typo: ResolvedTypography, extraLineSpacingPx: Float = 0f): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = typo.fontPx
            color = typo.fgColor
        }
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, typo.textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            // 行距%转成行间增量; extraLineSpacingPx = 垂直匀齐按页剩余空白摊到每行的增量(只增空隙,不改断行)
            .setLineSpacing(typo.lineExtraPx + extraLineSpacingPx, 1f)
            .setIncludePad(false)
            // 断行必须用贪心策略(SIMPLE): 贪心的行首只取决于前文,整章测量与单页重排
            // 截断出的行完全一致;HIGH_QUALITY 做全局平衡,截断文本与整章断行可能不同,
            // 导致页尾出现孤字。CJK 文本每行本就近乎排满,观感差异可忽略
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        // 两端对齐: API 26+ 的字间对齐,低版本退化为普通对齐
        if (typo.justify && android.os.Build.VERSION.SDK_INT >= 26) {
            builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        return builder.build()
    }

    // 虚拟章节(封面/末页)布局: 居中对齐、无缩进
    // 虚拟章节布局: 1.5 倍加粗字号 + 宽字间距(醒目)
    fun buildVirtualLayout(text: String, typo: ResolvedTypography): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = typo.fontPx * 1.5f
            color = typo.fgColor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.15f
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, typo.textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(typo.lineExtraPx, 1f)
            .setIncludePad(false)
            .build()
    }

}
