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

    // 断行/排版算法版本: 算法变化(如断行策略切换、行高贴合版心、页首豁免段前距)时 +1,使旧分页缓存失效
    const val BREAK_STRATEGY_VERSION = 9

    // 行高/段前距取整粒度(px): 向上取整到它的整数倍,取整只增不减,字形不被裁
    const val GRID_PX = 1

    // 页眉/页脚文字区高度(dp),悬浮于内容区上下留白内,不占版心
    const val TOP_GAP_DP: Int = 8
    const val PAGE_PADDING_DP: Int = 16
    // 页脚保留区: 与顶部 8+16=24dp 对称,正文底与页脚文字的间距
    const val FOOTER_GAP_DP: Int = 24

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

    // 构建 StaticLayout: 仅用于断行——BookPager 从中提取行字符区间生成行模型,
    // 绘制不走 StaticLayout(行高/基线/两端对齐由行模型与自绘负责)。
    // 测量(整章)与物化(同章行模型缓存)共用同一构建规则,断行结果必然一致
    fun buildLayout(text: CharSequence, typo: ResolvedTypography): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = typo.fontPx
            color = typo.fgColor
        }
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, typo.textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            // 断行必须用贪心策略(SIMPLE): 确定性断行——持久化的分页缓存(specs 页界)
            // 与后续物化时的重新断行必须逐字节一致,页界才不会漂移
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
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
