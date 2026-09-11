package com.yukino.tool.module.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

// 只负责画页: 页自包含(布局+页眉页脚),本类只做平移与层叠,不新建布局、不处理手势。
//
// 页面层序遵循书页物理序: 书序靠前的页在上层。因此覆盖动画:
//   下一页(dir=+1): 当前页(上层)向左跟手滑出,露出静止的目标页(下层)
//   上一页(dir=-1): 目标页(上层)从左跟手滑入,盖住静止的当前页(下层)
// 落影贴滑动页右缘。封面/封底越界方向页面静止(无拖拽反馈)。
//
// 动画纯视觉、状态先行落账: 松手翻页时调用方已切换状态,动画被新手势取消也不丢页。
// 封面/封底的越界方向不提供拖拽反馈(页面保持静止)
class ReaderPageView(context: Context) : View(context) {

    private var page: BookPage? = null
    private var typo: ResolvedTypography? = null

    // 内容区上下界(px): 页眉/正文/页脚画在其内;落影等装饰可超出到全屏。
    // 由调用方传入系统栏 inset(边到边模式下等于状态栏高度/导航条高度)
    // 内容区上下 inset(px): 页眉/正文/页脚画在 [topInset, height-bottomInset] 内;
    // 落影等装饰可超出到全屏。由调用方传入系统栏 inset
    private var topInsetPx = 0
    private var bottomInsetPx = 0

    // 拖拽层: 静止层(下层) + 滑动层(上层,跟手)。均为完整 BookPage
    private var dragStatic: BookPage? = null
    private var dragSlide: BookPage? = null
    private var dragDirection = 0
    private var dragOffset = 0f          // 手指水平位移原值(px)

    private var animator: ValueAnimator? = null

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chromePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val shadowWidthPx = (SHADOW_WIDTH_DP * context.resources.displayMetrics.density).toInt()
    private val pagePadPx = (Typography.PAGE_PADDING_DP * context.resources.displayMetrics.density).toInt()
    private val topGapPx = (TOP_GAP_DP * context.resources.displayMetrics.density).toInt()

    init {
        shadowPaint.shader = LinearGradient(
            0f, 0f, shadowWidthPx.toFloat(), 0f,
            SHADOW_COLOR, 0x00000000, Shader.TileMode.CLAMP
        )
    }

    fun setPage(page: BookPage, typo: ResolvedTypography) {
        // 重组会以相同参数重复调用;未变直接返回,避免把正在播放的翻页动画当场取消
        if (this.page === page && this.typo == typo) return
        this.page = page
        this.typo = typo
        invalidate()
    }

    fun setColors(typo: ResolvedTypography) {
        this.typo = typo
        invalidate()
    }

    fun setContentInsets(topPx: Int, bottomPx: Int) {
        if (topInsetPx == topPx && bottomInsetPx == bottomPx) return
        topInsetPx = topPx
        bottomInsetPx = bottomPx
        invalidate()
    }

    // 跟手: 每个拖拽事件调用。current=当前页,neighbor=手势开始时锁定的目标页
    fun showDrag(current: BookPage, neighbor: BookPage, direction: Int, dragX: Float) {
        animator?.cancel()
        dragDirection = direction
        dragOffset = dragX.coerceIn(-width.toFloat(), width.toFloat())
        if (direction > 0) {
            dragSlide = current
            dragStatic = neighbor
        } else {
            dragSlide = neighbor
            dragStatic = current
        }
        invalidate()
    }

    // 松手收尾: commit=true 把拖拽层顺势滑到翻页终点后清层(状态已由调用方先行提交);
    // false 弹回原位。动画结束时把终态可见页快照进 page——调用方对新页的重新物化是异步的,
    // 若直接清层回落到旧 page,结算帧会闪现旧页一瞬
    fun animateDragEnd(commit: Boolean) {
        val slide = dragSlide ?: return
        val static = dragStatic ?: return
        val dir = dragDirection
        val w = width.toFloat()
        animator?.cancel()
        val endDragX = when {
            dir > 0 && commit -> -w          // 下一页提交: 当前页完全滑出左缘
            dir > 0 -> 0f                    // 弹回: 当前页退回原位
            commit -> w                      // 上一页提交: 目标页完全盖入
            else -> 0f                       // 弹回: 目标页退回左缘外
        }
        // 终态可见页: 提交=目标页;弹回=当前页
        val settled = if (commit == (dir > 0)) static else slide
        val travel = abs(endDragX - dragOffset).coerceAtMost(w)
        animator = ValueAnimator.ofFloat(dragOffset, endDragX).apply {
            duration = (travel / w * COVER_DURATION_MS).toLong().coerceIn(MIN_ANIM_MS, COVER_DURATION_MS)
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                dragOffset = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    page = settled
                    dragStatic = null
                    dragSlide = null
                    dragOffset = 0f
                    invalidate()
                }
            })
            start()
        }
        dragSlide = slide
        dragStatic = static
    }

    // 书首/书末越界不提供拖拽反馈: 封面/封底页保持静止(产品约定)

    override fun onDetachedFromWindow() {
        cancelAnim()
        super.onDetachedFromWindow()
    }

    private fun cancelAnim() {
        animator?.cancel()
        animator = null
        dragStatic = null
        dragSlide = null
        dragOffset = 0f
    }

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        val t = typo ?: return
        canvas.drawColor(t.bgColor)

        val s = dragStatic
        val m = dragSlide
        if (s != null && m != null) {
            val w = width.toFloat()
            if (dragDirection > 0) {
                // 下一页: 当前页(滑动层)向左滑出,目标页静止在下;落影贴滑动页右缘
                drawPage(canvas, s, 0f, t)
                drawPage(canvas, m, dragOffset, t)
                drawEdgeShadow(canvas, dragOffset + w, t)
            } else {
                // 上一页: 目标页(滑动层)从左滑入盖住当前页;落影贴滑动页右缘
                drawPage(canvas, s, 0f, t)
                drawPage(canvas, m, dragOffset - w, t)
                drawEdgeShadow(canvas, dragOffset, t)
            }
            return
        }

        // 常态
        drawPage(canvas, p, 0f, t)
    }

    // 画一页: 页矩形不透明背景(覆盖时上层才能盖住下层) → 页眉/页脚(在上下留白内,不压正文)
    // → 内容区裁剪内画 layout(两端对齐哨兵行被底部裁剪裁掉)。封面/封底内容垂直居中
    private fun drawPage(canvas: Canvas, page: BookPage, offsetX: Float, t: ResolvedTypography) {
        val save = canvas.save()
        canvas.clipRect(offsetX, 0f, offsetX + width, height.toFloat())
        canvas.drawColor(t.bgColor)
        val textSize = CHROME_TEXT_SP * resources.displayMetrics.density
        val chromeColor = (138 shl 24) or (t.fgColor and 0x00FFFFFF)
        if (page.headerTitle.isNotEmpty()) {
            chromePaint.textSize = textSize
            chromePaint.color = chromeColor
            canvas.drawText(page.headerTitle, offsetX + t.marginPx, topInsetPx + pagePadPx - textSize * 0.35f, chromePaint)
        }
        if (page.footerLabel.isNotEmpty()) {
            chromePaint.textSize = textSize
            chromePaint.color = chromeColor
            canvas.drawText(page.footerLabel, offsetX + t.marginPx, height - bottomInsetPx + textSize * 0.9f, chromePaint)
        }
        val save2 = canvas.save()
        // 顶部加 8dp: 页眉与正文首行拉开间距
        val contentTop = topInsetPx + pagePadPx.toFloat() + topGapPx
        val contentBottom = (height - bottomInsetPx).toFloat()
        canvas.clipRect(offsetX, contentTop, offsetX + width, contentBottom)
        val y = if (page.spec.kind == PageKind.CONTENT) contentTop + page.topOffsetPx
        else contentTop + (height - topInsetPx - bottomInsetPx - 2 * pagePadPx - page.layout.height) / 2f
        canvas.translate(offsetX + t.marginPx, y)
        page.layout.draw(canvas)
        canvas.restoreToCount(save2)
        canvas.restoreToCount(save)
    }

    // 落影: 画在滑动页右缘外侧的下层露出区,越贴近页缘越深;纵贯整个屏幕高度
    private fun drawEdgeShadow(canvas: Canvas, x: Float, t: ResolvedTypography) {
        if (x <= 0f || x >= width || shadowWidthPx <= 0) return
        val save = canvas.save()
        canvas.translate(x, 0f)
        canvas.drawRect(0f, 0f, shadowWidthPx.toFloat(), height.toFloat(), shadowPaint)
        canvas.restoreToCount(save)
    }

    companion object {
        private const val COVER_DURATION_MS = 260L
        private const val MIN_ANIM_MS = 90L
        private const val SHADOW_WIDTH_DP = 12f
        private const val SHADOW_COLOR = 0x33000000
        private const val CHROME_TEXT_SP = 12f
        private const val TOP_GAP_DP = 8f   // 页眉与正文首行的额外间距
    }
}
