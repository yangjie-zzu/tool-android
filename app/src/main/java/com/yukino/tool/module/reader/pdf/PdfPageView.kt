package com.yukino.tool.module.reader.pdf

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs

// PDF 页绘制 View: 只负责画页面位图 + 拖拽滑动翻页层叠(与文本流的 ReaderPageView 平行,
// 复用同一交互语义: showDrag 跟手 / animateDragEnd 收尾)。位图由宿主(PdfReaderPage)渲染提供。
// 页面在内容区内 fit-center(宽铺满, 高度不足居中;超高整页贴顶,不支持页内滚动——
// 版式一阶段按"整页适配"呈现)。
class PdfPageView(context: Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint()

    private var bgColor = Color.WHITE
    private var current: Bitmap? = null

    // 拖拽会话: dir +1=下一页(左滑,新页从右滑入) / -1=上一页(右滑,新页从左滑入)
    private var dragDir = 0
    private var dragOffset = 0f
    private var dragCur: Bitmap? = null
    private var dragNeighbor: Bitmap? = null
    private var animator: ValueAnimator? = null

    // 内容区(系统栏避让后): 页面位图绘制范围
    private var contentTop = 0
    private var contentBottom = 0

    fun setContentInsets(top: Int, bottom: Int) {
        if (contentTop != top || contentBottom != bottom) {
            contentTop = top
            contentBottom = height - bottom
            invalidate()
        }
    }

    fun setColors(bg: Int) {
        bgColor = bg
        invalidate()
    }

    fun setPage(bmp: Bitmap?) {
        current = bmp
        invalidate()
    }

    fun showDrag(cur: Bitmap, neighbor: Bitmap, dir: Int, offset: Float) {
        animator?.cancel()
        dragDir = dir
        dragOffset = offset
        dragCur = cur
        dragNeighbor = neighbor
        invalidate()
    }

    fun cancelDrag() {
        animator?.cancel()
        dragCur = null
        dragNeighbor = null
        dragOffset = 0f
        invalidate()
    }

    // 松手收尾: flip=true 翻页落账(页面滑出), false 弹回。onDone 在动画结束回调(主线程)
    fun animateDragEnd(flip: Boolean, onDone: () -> Unit) {
        val from = dragOffset
        val to = if (flip) -sign() * width.toFloat() else 0f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = 260
            addUpdateListener {
                dragOffset = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dragCur = null
                    dragNeighbor = null
                    dragOffset = 0f
                    invalidate()
                    onDone()
                }
            })
            start()
        }
    }

    private fun sign(): Float = if (dragOffset < 0) -1f else if (dragOffset > 0) 1f else 1f

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val cur = dragCur ?: run {
            current?.let { drawFit(canvas, it, 0f, dim(false)) }
            return
        }
        // 拖拽中: 当前页随手指平移, 邻页贴边待命 + 当前页背面加暗(与文本翻页一致的层叠观感)
        val neighbor = dragNeighbor
        val w = width.toFloat()
        val moving = dragOffset
        if (dragDir < 0) {
            // 右滑上一页: 邻页在左侧, 从左边跟着滑入
            neighbor?.let { drawFit(canvas, it, moving - w, dim(false)) }
            drawFit(canvas, cur, moving, dim(true))
        } else {
            // 左滑下一页: 邻页在右侧
            neighbor?.let { drawFit(canvas, it, moving + w, dim(false)) }
            drawFit(canvas, cur, moving, dim(true))
        }
    }

    private fun dim(shaded: Boolean): Int =
        if (shaded) ((abs(dragOffset) / width.coerceAtLeast(1)) * 64).toInt().coerceIn(0, 64) else 0

    // 页面位图在内容区内 fit-center 绘制, 水平平移 dx;shade = 覆盖黑色遮罩 alpha
    private fun drawFit(canvas: Canvas, bmp: Bitmap, dx: Float, shade: Int) {
        val areaW = width.toFloat()
        val areaH = (contentBottom - contentTop).coerceAtLeast(1).toFloat()
        val scale = minOf(areaW / bmp.width, areaH / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (areaW - w) / 2f + dx
        val top = contentTop + (areaH - h) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), paint)
        if (shade > 0) {
            dimPaint.color = Color.argb(shade, 0, 0, 0)
            canvas.drawRect(left, top, left + w, top + h, dimPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
