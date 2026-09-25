package com.yukino.tool.module.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    // 选择层: 高亮矩形(版心坐标系,与 DrawLine 同一空间)+ 跨页延续标志。
    // 手柄由上层 Compose 组件绘制与交互,本类只画高亮与边缘指示条
    private var selRects: List<android.graphics.RectF>? = null
    private var selExtendsTop = false
    private var selExtendsBottom = false
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chromePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 电量百分比(null=未获取): 系统电量广播驱动;时间由分钟定时器刷新
    private var batteryPct: Int? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPct = level * 100 / scale
                invalidate()
            }
        }
    }

    private val minuteTicker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    private val shadowWidthPx = (SHADOW_WIDTH_DP * context.resources.displayMetrics.density).toInt()
    private val pagePadPx = (Typography.PAGE_PADDING_DP * context.resources.displayMetrics.density).toInt()
    private val topGapPx = (Typography.TOP_GAP_DP * context.resources.displayMetrics.density).toInt()
    private val footerGapPx = (Typography.FOOTER_GAP_DP * context.resources.displayMetrics.density).toInt()

    init {
        shadowPaint.shader = LinearGradient(
            0f, 0f, shadowWidthPx.toFloat(), 0f,
            SHADOW_COLOR, 0x00000000, Shader.TileMode.CLAMP
        )
        titlePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun setPage(page: BookPage, typo: ResolvedTypography) {
        // 重组会以相同参数重复调用;未变直接返回,避免把正在播放的翻页动画当场取消
        if (this.page === page && this.typo == typo) return
        this.page = page
        this.typo = typo
        configurePaints(typo)
        invalidate()
    }

    fun setColors(typo: ResolvedTypography) {
        this.typo = typo
        configurePaints(typo)
        invalidate()
    }

    // 行绘制 paint: 正文/标题两套字号;颜色随版式(主题切换)更新
    private fun configurePaints(t: ResolvedTypography) {
        bodyPaint.textSize = t.fontPx
        bodyPaint.color = t.fgColor
        titlePaint.textSize = t.fontPx * ChapterComposer.TITLE_SCALE
        titlePaint.color = t.fgColor
    }

    fun setContentInsets(topPx: Int, bottomPx: Int) {
        if (topInsetPx == topPx && bottomInsetPx == bottomPx) return
        topInsetPx = topPx
        bottomInsetPx = bottomPx
        invalidate()
    }

    // 设置选择高亮(rects 为版心坐标系;null 清除)。color 为高亮填充色(前景色低透明度)
    fun setSelection(rects: List<android.graphics.RectF>?, extendsTop: Boolean, extendsBottom: Boolean, color: Int) {
        val same = selRects == rects && selExtendsTop == extendsTop && selExtendsBottom == extendsBottom &&
            selPaint.color == color
        if (same) return
        selRects = rects
        selExtendsTop = extendsTop
        selExtendsBottom = extendsBottom
        selPaint.color = color
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

    // 电量广播 + 分钟定时: 页脚右侧时间/电量的数据源。注册即收到系统 sticky 电量广播,
    // 拿到初始电量;定时器对齐到下一分钟整,保证时间分钟级准确
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ContextCompat.registerReceiver(
            context, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        postDelayed(minuteTicker, 60_000 - System.currentTimeMillis() % 60_000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(minuteTicker)
        runCatching { context.unregisterReceiver(batteryReceiver) }
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
    // → 内容区裁剪内逐行画行模型(基线坐标物化时算好,此处只平移)。
    // 封面/封底画居中的虚拟布局,内容垂直居中
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
            //页脚文字画在保留区内,距屏幕底部再留出边距,不贴底
            canvas.drawText(
                page.footerLabel, offsetX + t.marginPx,
                height - bottomInsetPx - footerGapPx * 0.2f, chromePaint
            )
        }
        // 页脚右侧: 时间 + 电量图标(正文页;时间/电量是动态信息,绘制时实时取)
        if (page.spec.kind == PageKind.CONTENT) {
            chromePaint.textSize = textSize
            chromePaint.color = chromeColor
            val baseline = height - bottomInsetPx - footerGapPx * 0.2f
            var right = offsetX + width - t.marginPx
            val pct = batteryPct
            if (pct != null) {
                val iconW = textSize * 0.68f * 2.3f
                drawBattery(canvas, right, baseline, iconW, textSize * 0.68f, pct, chromeColor)
                right -= iconW + 10f
            }
            val timeText = timeFormat.format(Date())
            right -= chromePaint.measureText(timeText)
            canvas.drawText(timeText, right, baseline, chromePaint)
        }
        val save2 = canvas.save()
        // 顶部加 8dp: 页眉与正文首行拉开间距
        val contentTop = topInsetPx + pagePadPx.toFloat() + topGapPx
        val contentBottom = (height - bottomInsetPx - footerGapPx).toFloat()
        canvas.clipRect(offsetX, contentTop, offsetX + width, contentBottom)
        val virtual = page.virtualLayout
        if (virtual != null) {
            val y = contentTop + (height - topInsetPx - bottomInsetPx - 2 * pagePadPx - virtual.height) / 2f
            canvas.translate(offsetX + t.marginPx, y)
            virtual.draw(canvas)
        } else {
            canvas.translate(offsetX + t.marginPx, contentTop)
            for (ln in page.lines) {
                val paint = if (ln.title) titlePaint else bodyPaint
                val segs = ln.segments
                if (segs == null) {
                    canvas.drawText(ln.text, ln.x, ln.baseline, paint)
                } else {
                    // 两端对齐行: 物化时按词元拉伸算好的分段直接画
                    for (seg in segs) canvas.drawText(seg.text, ln.x + seg.x, ln.baseline, paint)
                }
            }
            // 选择高亮: 画在正文之后(叠加),版心坐标直接用
            selRects?.let { rs ->
                for (r in rs) canvas.drawRoundRect(r, 6f, 6f, selPaint)
            }
        }
        canvas.restoreToCount(save2)
        // 跨页延续指示: 选区延伸到上/下一页时,在版心对应缘画一条窄条提示
        val rects = selRects
        if (rects != null && page.spec.kind == PageKind.CONTENT) {
            val barH = (INDICATOR_BAR_DP * resources.displayMetrics.density)
            val left = t.marginPx.toFloat()
            val right = (width - t.marginPx).toFloat()
            if (selExtendsTop) {
                canvas.drawRect(left, contentTop - barH * 2f, right, contentTop - barH, selPaint)
            }
            if (selExtendsBottom) {
                canvas.drawRect(left, contentBottom + barH, right, contentBottom + barH * 2f, selPaint)
            }
        }
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

    // 电池图标: 右缘对齐 right、底边贴文字基线;外框+正极凸头描边,内部按电量填充,
    // 低电量(≤15%)填充转红。尺寸随页脚文字字号缩放
    private fun drawBattery(
        canvas: Canvas, right: Float, baseline: Float,
        w: Float, h: Float, pct: Int, color: Int
    ) {
        val top = baseline - h
        val bodyW = w - h * 0.18f
        batteryPaint.color = color
        batteryPaint.style = Paint.Style.STROKE
        batteryPaint.strokeWidth = Math.max(1.5f, h * 0.09f)
        canvas.drawRoundRect(right - bodyW, top, right - h * 0.18f, baseline, h * 0.12f, h * 0.12f, batteryPaint)
        batteryPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            right - h * 0.12f, top + h * 0.30f, right, baseline - h * 0.30f,
            h * 0.06f, h * 0.06f, batteryPaint
        )
        val inset = h * 0.16f
        val fillW = (bodyW - inset * 2f) * pct / 100f
        if (fillW > 1f) {
            batteryPaint.color = if (pct <= 15) 0xFFE53935.toInt() else color
            canvas.drawRoundRect(
                right - bodyW + inset, top + inset, right - bodyW + inset + fillW, baseline - inset,
                h * 0.06f, h * 0.06f, batteryPaint
            )
        }
    }

    companion object {
        private const val COVER_DURATION_MS = 260L
        private const val MIN_ANIM_MS = 90L
        private const val SHADOW_WIDTH_DP = 12f
        private const val SHADOW_COLOR = 0x33000000
        private const val CHROME_TEXT_SP = 12f
        private const val INDICATOR_BAR_DP = 3f
    }
}
