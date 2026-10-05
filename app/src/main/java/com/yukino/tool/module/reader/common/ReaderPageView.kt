package com.yukino.tool.module.reader.common

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
    private val decorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 样式段衍生 paint(样式段全特征 → paint;configurePaints 时整体失效重建)。
    // 七期批次三: 字号倍率/颜色/阴影/字体参与 key
    private data class PaintKey(
        val title: Boolean, val style: Int, val sizeEm: Float?,
        val color: Long?, val shadow: Boolean, val font: String?
    )
    private val stylePaints = HashMap<PaintKey, android.text.TextPaint>()

    // @font-face 字体缓存(family → Typeface;加载失败记录避免反复读盘)与设置 paint
    private val typefaceCache = HashMap<String, android.graphics.Typeface>()
    private val failedFonts = HashSet<String>()
    private fun typefaceFor(family: String, path: String): android.graphics.Typeface? {
        typefaceCache[family]?.let { return it }
        if (family in failedFonts) return null
        val tf = runCatching { android.graphics.Typeface.createFromFile(path) }.getOrNull()
        if (tf == null) {
            failedFonts.add(family)
            return null
        }
        typefaceCache[family] = tf
        return tf
    }

    // 图片缓存: imageRef → 解码 Bitmap(LRU,总字节超限逐出最老;换书/版式变化不失效——
    // 同一路径解码结果不变,翻页反复命中)。解码单线程后台,未命中画占位框,完成后重绘
    private val imageCache = object : LinkedHashMap<String, android.graphics.Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>): Boolean {
            if (byteTotal() <= IMAGE_CACHE_BYTES) return false
            // 逐出最老一项后仍可能超限(单图超限保留——总有一张可画)
            return true
        }

        private fun byteTotal(): Long = values.sumOf { it.byteCount.toLong() }
    }
    private val pendingDecodes = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val imageDecoder = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "reader-image")
    }

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
        stylePaints.clear()   // 衍生 paint 带字号/颜色,版式变化重建
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
        val cover = page.coverImage
        val virtual = page.virtualLayout
        val deco = page.decoImage
        when {
            deco != null && page.decoWidth > 0 && page.decoHeight > 0 -> {
                // 装饰章快照: 版心内等比居中(尺寸物化时按版心宽换算好);夜间套反色滤镜
                val availH = height - topInsetPx - bottomInsetPx - 2 * pagePadPx
                val x = offsetX + t.marginPx + (t.textWidth - page.decoWidth) / 2f
                val y = contentTop + (availH - page.decoHeight) / 2f
                drawDecoBitmap(canvas, deco, x, y, page.decoWidth.toFloat(), page.decoHeight.toFloat(), t.night)
            }
            cover != null && page.coverWidth > 0 && page.coverHeight > 0 -> {
                // 封面页: 图片在版心内等比居中(尺寸物化时按版心宽换算好)
                val availH = height - topInsetPx - bottomInsetPx - 2 * pagePadPx
                val x = offsetX + t.marginPx + (t.textWidth - page.coverWidth) / 2f
                val y = contentTop + (availH - page.coverHeight) / 2f
                drawBitmapFit(canvas, cover, x, y, page.coverWidth.toFloat(), page.coverHeight.toFloat())
            }
            virtual != null -> {
                val y = contentTop + (height - topInsetPx - bottomInsetPx - 2 * pagePadPx - virtual.height) / 2f
                canvas.translate(offsetX + t.marginPx, y)
                virtual.draw(canvas)
            }
            else -> {
            canvas.translate(offsetX + t.marginPx, contentTop)
            // 七期: 盒组矩形(底色/背景图/边框/圆角/阴影)画在文字下层
            for (b in page.boxes) drawBoxShape(canvas, b, t)
            // 批次四: 表格片段(同样在文字下层;表格行不产文字行)
            for (tb in page.tables) drawTable(canvas, tb, t)
            for (ln in page.lines) {
                if (ln.blockRef != null) {
                    drawBlockBitmap(canvas, ln, t)
                    continue
                }
                if (ln.imageRef != null) {
                    drawImageLine(canvas, ln)
                    continue
                }
                val base = if (ln.title) titlePaint else bodyPaint
                if (ln.inlineImages.isNotEmpty()) {
                    drawInlineLine(canvas, ln, base)
                    continue
                }
                if (ln.styles == null) {
                    val segs = ln.segments
                    if (segs == null) {
                        canvas.drawText(ln.text, ln.x, ln.baseline, base)
                    } else {
                        // 两端对齐行: 物化时按词元拉伸算好的分段直接画
                        for (seg in segs) canvas.drawText(seg.text, ln.x + seg.x, ln.baseline, base)
                    }
                } else {
                    drawStyledLine(canvas, ln, base)
                }
            }
            // 选择高亮: 画在正文之后(叠加),版心坐标直接用
            selRects?.let { rs ->
                for (r in rs) canvas.drawRoundRect(r, 6f, 6f, selPaint)
            }
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

    // 装饰章快照绘制: 与封面同族的 contain 绘制;夜间套原 WebView 呈现同参数的反色滤镜
    private val decoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var nightDecoFilter: android.graphics.ColorFilter? = null

    // 混合渲染: WEBVIEW 块位图行(与装饰章快照同族绘制 + 夜间同参数反色滤镜;
    // 位图上叠画选择高亮由 selRects 统一承担)
    private fun drawBlockBitmap(canvas: Canvas, ln: DrawLine, t: ResolvedTypography) {
        val w = ln.blockWidth
        val h = ln.blockHeight
        if (w <= 0f || h <= 0f) return
        val bmp = imageFor(ln.blockRef ?: return, w.toInt(), h.toInt())
        if (bmp == null) {
            drawPlaceholder(canvas, ln.x, ln.baseline, w, h)
            return
        }
        if (t.night) {
            if (nightDecoFilter == null) nightDecoFilter = android.graphics.ColorMatrixColorFilter(nightDecoMatrix())
            decoPaint.colorFilter = nightDecoFilter
        } else {
            decoPaint.colorFilter = null
        }
        canvas.drawBitmap(bmp, null, android.graphics.RectF(ln.x, ln.baseline, ln.x + w, ln.baseline + h), decoPaint)
    }

    private fun drawDecoBitmap(canvas: Canvas, ref: String, x: Float, y: Float, w: Float, h: Float, night: Boolean) {
        val bmp = imageFor(ref, w.toInt(), h.toInt())
        if (bmp == null) {
            drawPlaceholder(canvas, x, y, w, h)
            return
        }
        if (night) {
            if (nightDecoFilter == null) nightDecoFilter = android.graphics.ColorMatrixColorFilter(nightDecoMatrix())
            decoPaint.colorFilter = nightDecoFilter
        } else {
            decoPaint.colorFilter = null
        }
        canvas.drawBitmap(bmp, null, android.graphics.RectF(x, y, x + w, y + h), decoPaint)
    }

    // invert(0.92) × hue-rotate(180°): 白底变暗、彩色近似保留(与原 WebView 滤镜同参数)
    private fun nightDecoMatrix(): android.graphics.ColorMatrix {
        val inv = android.graphics.ColorMatrix(
            floatArrayOf(
                -0.84f, 0f, 0f, 0f, 234.6f,
                0f, -0.84f, 0f, 0f, 234.6f,
                0f, 0f, -0.84f, 0f, 234.6f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val c = -1f   // cos(180°)
        val hue = android.graphics.ColorMatrix(
            floatArrayOf(
                0.213f + 0.787f * c, 0.715f - 0.715f * c, 0.072f - 0.072f * c, 0f, 0f,
                0.213f - 0.213f * c, 0.715f + 0.285f * c, 0.072f - 0.072f * c, 0f, 0f,
                0.213f - 0.213f * c, 0.715f - 0.715f * c, 0.072f + 0.928f * c, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        hue.setConcat(hue, inv)   // 先反色再转色相
        return hue
    }

    // 落影: 画在滑动页右缘外侧的下层露出区,越贴近页缘越深;纵贯整个屏幕高度
    private fun drawEdgeShadow(canvas: Canvas, x: Float, t: ResolvedTypography) {
        if (x <= 0f || x >= width || shadowWidthPx <= 0) return
        val save = canvas.save()
        canvas.translate(x, 0f)
        canvas.drawRect(0f, 0f, shadowWidthPx.toFloat(), height.toFloat(), shadowPaint)
        canvas.restoreToCount(save)
    }

    // ---------- 富文本与图片绘制(二期) ----------

    // 样式段衍生 paint: 相对 base 调整字形/字号/颜色/阴影/字体。
    // 字号 = base × 书内倍率 ×(上下标再乘 0.65);颜色夜间主题做亮度适配;
    // 书内字体(@font-face)与粗斜位组合;阴影近似 text-shadow(1px 1px 微光晕)
    private fun stylePaintFor(base: android.text.TextPaint, title: Boolean, st: LineStyle): android.text.TextPaint {
        val key = PaintKey(title, st.style, st.sizeEm, st.color, st.shadow, st.font)
        stylePaints[key]?.let { return it }
        val p = android.text.TextPaint(base)
        val bold = st.style and RunStyle.BOLD != 0
        val italic = st.style and RunStyle.ITALIC != 0
        val bookFace = st.font?.let { fam -> page?.fontFiles?.get(fam)?.let { typefaceFor(fam, it) } }
        p.typeface = when {
            bookFace != null && bold && italic -> Typeface.create(bookFace, Typeface.BOLD_ITALIC)
            bookFace != null && bold -> Typeface.create(bookFace, Typeface.BOLD)
            bookFace != null && italic -> Typeface.create(bookFace, Typeface.ITALIC)
            bookFace != null -> bookFace
            bold && italic -> Typeface.create(base.typeface, Typeface.BOLD_ITALIC)
            bold -> Typeface.create(base.typeface, Typeface.BOLD)
            italic -> Typeface.create(base.typeface, Typeface.ITALIC)
            else -> base.typeface
        }
        var size = base.textSize
        st.sizeEm?.let { size *= it }
        if (st.style and (RunStyle.SUP or RunStyle.SUB) != 0) size *= ChapterComposer.SUP_SUB_SCALE
        p.textSize = size
        st.color?.let { c -> p.color = adaptColor(c, typo?.night == true).toInt() }
        if (st.shadow) {
            p.setShadowLayer(size * 0.08f, 1f, 1f, 0xB3000000.toInt())
        }
        stylePaints[key] = p
        return p
    }

    // 上下标基线偏移: 上标上移(字号 1/4),下标下移(字号 0.15)
    private fun baselineShift(style: Int, fontSize: Float): Float = when {
        style and RunStyle.SUP != 0 -> -fontSize * 0.25f
        style and RunStyle.SUB != 0 -> fontSize * 0.15f
        else -> 0f
    }

    // 五期: 行内图片行——按占位符(U+FFFC)把行切成"文本段/图片位"逐段绘制。
    // 字符 x 定位: 自然宽行按前缀逐字符累计;两端对齐行按 seg.x 锚点 + 段内前缀宽。
    // 文本段的样式/基线偏移按字符所在样式段折算(与 drawStyledLine 同一 stylePaintFor 缓存);
    // 图片底边贴基线下沉一点,近似行内小图的视觉位置。
    // 已知限制: 该行的选区度量(selMetrics 纯文本 measure)不含图片真实宽度,角标字符处选区略有偏差
    private fun drawInlineLine(canvas: Canvas, ln: DrawLine, base: android.text.TextPaint) {
        val styles = ln.styles
        val inlines = ln.inlineImages.sortedBy { it.charIdx }

        fun paintAt(charIdx: Int): android.text.TextPaint {
            if (styles != null) {
                for (st in styles) {
                    if (charIdx >= st.start && charIdx < st.end &&
                        (st.style != 0 || st.sizeEm != null || st.color != null || st.shadow || st.font != null)
                    ) {
                        return stylePaintFor(base, ln.title, st)
                    }
                }
            }
            return base
        }

        fun dyAt(charIdx: Int): Float {
            if (styles != null) {
                for (st in styles) {
                    if (charIdx >= st.start && charIdx < st.end) {
                        return baselineShift(st.style, paintAt(charIdx).textSize)
                    }
                }
            }
            return 0f
        }

        fun charX(charIdx: Int): Float {
            val segs = ln.segments
            if (segs == null) {
                var acc = 0f
                for (i in 0 until charIdx) acc += paintAt(i).measureText(ln.text[i].toString())
                return ln.x + acc
            }
            var cursor = 0
            for (seg in segs) {
                val segLen = seg.text.length
                if (charIdx < cursor + segLen) {
                    var acc = 0f
                    for (i in cursor until charIdx) acc += paintAt(i).measureText(ln.text[i].toString())
                    return ln.x + seg.x + acc
                }
                cursor += segLen
            }
            return ln.x
        }

        fun drawTextRange(from: Int, until: Int) {
            var i = from
            while (i < until) {
                val p = paintAt(i)
                var j = i + 1
                while (j < until && paintAt(j) === p) j++
                canvas.drawText(ln.text.substring(i, j), charX(i), ln.baseline + dyAt(i), p)
                i = j
            }
        }

        var cursor = 0
        for (inl in inlines) {
            if (inl.charIdx > cursor) drawTextRange(cursor, inl.charIdx)
            if (inl.width > 0 && inl.height > 0) {
                val bmp = imageFor(inl.ref, inl.width, inl.height)
                val left = charX(inl.charIdx)
                // sup(noteref 图片角标)按上标形态提升: 底缘在基线上方 0.12 字号;
                // 普通行内图底缘在基线下方 0.18 字号(垂直居中于字身)
                val top = ln.baseline - inl.height +
                    base.textSize * (if (inl.sup) -0.12f else 0.18f)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + inl.width, top + inl.height), imagePaint)
                } else {
                    drawPlaceholder(canvas, left, top, inl.width.toFloat(), inl.height.toFloat())
                }
            }
            cursor = inl.charIdx + 1
        }
        if (cursor < ln.text.length) drawTextRange(cursor, ln.text.length)
    }

    // 带样式行: 逐样式段绘制;段内若有两端对齐拉伸分段,按词元字符游标裁出子段。
    // 段起点 x = 行首 x + 前缀宽度(前缀跨样式时按本段 paint 量,词元边界处精确)
    private fun drawStyledLine(canvas: Canvas, ln: DrawLine, base: android.text.TextPaint) {        val styles = ln.styles ?: return
        val full = ln.text
        // 段起始 x: 前缀按"各字符所在段自己的 paint"量宽(样式段字号不同时,
        // 统一用 base 量会让后段起点前移/后移造成覆盖——七期字号混排修复)
        fun prefixX(upTo: Int): Float {
            var acc = 0f
            var i = 0
            while (i < upTo) {
                val st = styles.firstOrNull { i >= it.start && i < it.end }
                val p = if (st != null) stylePaintFor(base, ln.title, st) else base
                val end = if (st != null) minOf(st.end, upTo) else upTo
                acc += p.measureText(full, i, end)
                i = end
            }
            return acc
        }
        for (st in styles) {
            val p = stylePaintFor(base, ln.title, st)
            val dy = baselineShift(st.style, p.textSize)
            val underline = st.style and RunStyle.UNDERLINE != 0
            val strike = st.style and RunStyle.STRIKE != 0
            val segs = ln.segments
            if (segs == null) {
                if (st.start >= st.end || st.end > full.length) continue
                val x = ln.x + prefixX(st.start)
                val sub = full.substring(st.start, st.end)
                canvas.drawText(sub, x, ln.baseline + dy, p)
                drawDecor(canvas, p, x, ln.baseline + dy, sub, underline, strike, p.textSize)
            } else {
                var cursor = 0
                for (seg in segs) {
                    val segEnd = cursor + seg.text.length
                    val hit = segEnd > st.start && cursor < st.end
                    val a = (st.start - cursor).coerceIn(0, seg.text.length)
                    val b = (st.end - cursor).coerceIn(0, seg.text.length)
                    cursor = segEnd
                    if (!hit || a >= b) continue
                    val x = ln.x + seg.x + p.measureText(seg.text, 0, a)
                    val sub = seg.text.substring(a, b)
                    canvas.drawText(sub, x, ln.baseline + dy, p)
                    drawDecor(canvas, p, x, ln.baseline + dy, sub, underline, strike, p.textSize)
                }
            }
        }
    }

    // 下划线/删除线: 贴基线下方/中线附近画线,粗细随字号
    private fun drawDecor(
        canvas: Canvas, p: android.text.TextPaint, x: Float, baseline: Float,
        text: String, underline: Boolean, strike: Boolean, fontSize: Float
    ) {
        if (!underline && !strike) return
        val w = p.measureText(text)
        decorPaint.color = p.color
        decorPaint.strokeWidth = maxOf(1f, fontSize * 0.055f)
        if (underline) {
            val y = baseline + fontSize * 0.12f
            canvas.drawLine(x, y, x + w, y, decorPaint)
        }
        if (strike) {
            val y = baseline - fontSize * 0.28f
            canvas.drawLine(x, y, x + w, y, decorPaint)
        }
    }

    // ---------- 七期: 盒组绘制(底色/背景图/边框/圆角/阴影) ----------

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgImagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // 夜间主题颜色适配: 亮度反转(保持色相)——深底变浅、浅底变深,书内色块在暗背景下可读
    private fun adaptColor(color: Long, night: Boolean): Long {
        if (!night) return color
        val a = (color ushr 24) and 0xFFL
        val r = 255L - ((color ushr 16) and 0xFFL)
        val g = 255L - ((color ushr 8) and 0xFFL)
        val b = 255L - (color and 0xFFL)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // 立体边框(ridge/groove/inset/outset)的亮/暗分量: 亮 = 向白靠拢 40%,暗 = 压暗 40%
    private fun shade(color: Long, lighten: Boolean): Long {
        val a = (color ushr 24) and 0xFFL
        fun adj(c: Long): Long = if (lighten) c + ((255L - c) * 2L / 5L) else c * 3L / 5L
        val r = adj((color ushr 16) and 0xFFL)
        val g = adj((color ushr 8) and 0xFFL)
        val b = adj(color and 0xFFL)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // CSS border-radius 的角半径: 椭圆角(rx 水平/ry 垂直可不同)
    private class EllipseRadius(val rx: Float, val ry: Float)

    // 百分比水平相对盒宽/垂直相对盒高,且**不缩减**——转制书 border-radius:100% 的设计意图
    // 就是角弧覆盖整条边(气泡轮廓,与封面一致);em/px 固定值按圆角处理并钳制到边长一半
    private fun ellipseRadiusOf(style: com.yukino.tool.module.reader.common.BoxStyle, rect: android.graphics.RectF, fontPx: Float): EllipseRadius? {
        val rad = style.radius ?: return null
        val rx = if (rad.pct) rect.width() * rad.v / 100f
        else (rad.px(fontPx, rect.width())?.coerceAtLeast(0f) ?: 0f).coerceAtMost(rect.width() / 2f)
        val ry = if (rad.pct) rect.height() * rad.v / 100f
        else (rad.px(fontPx, rect.width())?.coerceAtLeast(0f) ?: rx).coerceAtMost(rect.height() / 2f)
        if (rx <= 0f || ry <= 0f) return null
        return EllipseRadius(rx, ry)
    }

    // 圆角矩形闭合轮廓(角为椭圆弧 rx/ry;rx=ry 即圆角)
    private fun roundOutline(rect: android.graphics.RectF, rx: Float, ry: Float): android.graphics.Path {
        val p = android.graphics.Path()
        val L = rect.left; val T = rect.top; val w = rect.width(); val h = rect.height()
        p.moveTo(L + rx, T)
        p.lineTo(L + w - rx, T)
        p.arcTo(android.graphics.RectF(L + w - 2 * rx, T, L + w, T + 2 * ry), 270f, 90f)
        p.lineTo(L + w, T + h - ry)
        p.arcTo(android.graphics.RectF(L + w - 2 * rx, T + h - 2 * ry, L + w, T + h), 0f, 90f)
        p.lineTo(L + rx, T + h)
        p.arcTo(android.graphics.RectF(L, T + h - 2 * ry, L + 2 * rx, T + h), 90f, 90f)
        p.lineTo(L, T + ry)
        p.arcTo(android.graphics.RectF(L, T, L + 2 * rx, T + 2 * ry), 180f, 90f)
        p.close()
        return p
    }

    private fun drawBoxShape(canvas: Canvas, box: DrawBox, t: ResolvedTypography) {
        val night = t.night
        val style = box.style
        val fontPx = t.fontPx
        val rect = android.graphics.RectF(box.left, box.top, box.right, box.bottom)
        val radius = ellipseRadiusOf(style, rect, fontPx)
        // 椭圆模式: 角半径达到对应边长一半(border-radius:100% 缩减语义的极限形态),
        // 轮廓即椭圆本身——fill/uniform 用整椭圆,部分边框用椭圆象限弧(四角弧拼接在此尺度
        // 会来自不同圆心而互相交叉出花纹)
        val ellipseMode = radius != null &&
            (radius.rx >= rect.width() / 2f - 0.5f || radius.ry >= rect.height() / 2f - 0.5f)
        // 批次四d: 盒旋转(绕盒中心;选区命中不走盒内,装饰性元素)
        val rotated = style.rotateDeg != null && style.rotateDeg != 0f
        val saveCount = if (rotated) {
            canvas.save()
            canvas.rotate(style.rotateDeg!!, rect.centerX(), rect.centerY())
            1
        } else 0
        boxPaint.style = Paint.Style.FILL
        boxPaint.shadowLayerCompatClear()

        // 底色(阴影挂在底色填充上;无底色的阴影画一层近透明填充承载)
        style.bg?.let { c ->
            boxPaint.color = adaptColor(c, night).toInt()
            if (style.shadow) {
                boxPaint.setShadowLayer(fontPx * 0.16f, fontPx * 0.1f, fontPx * 0.14f, 0x55000000)
            }
            fillRound(canvas, rect, radius, boxPaint)
            boxPaint.shadowLayerCompatClear()
        } ?: run {
            if (style.shadow) {
                boxPaint.color = 0x01000000
                boxPaint.setShadowLayer(fontPx * 0.18f, fontPx * 0.1f, fontPx * 0.16f, 0x55000000)
                fillRound(canvas, rect, radius, boxPaint)
                boxPaint.shadowLayerCompatClear()
            }
        }
        // 背景图: cover 铺满盒矩形(等比放缩到覆盖,居中裁剪;夜间压暗 55%)
        val bgRef = style.bgImage
        if (bgRef != null && rect.width() > 1f && rect.height() > 1f) {
            val bmp = imageFor(bgRef, rect.width().toInt(), rect.height().toInt())
            if (bmp != null) {
                val scale = maxOf(rect.width() / bmp.width, rect.height() / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val sx = (bmp.width - rect.width() / scale) / 2f
                val sy = (bmp.height - rect.height() / scale) / 2f
                if (night) {
                    val cm = android.graphics.ColorMatrix().apply { setScale(0.45f, 0.45f, 0.45f, 1f) }
                    bgImagePaint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                } else {
                    bgImagePaint.colorFilter = null
                }
                canvas.drawBitmap(
                    bmp,
                    android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + rect.width() / scale).toInt(), (sy + rect.height() / scale).toInt()),
                    rect, bgImagePaint
                )
            } else {
                drawPlaceholder(canvas, rect.left, rect.top, rect.width(), rect.height())
            }
        }

        // 四边边框: 上右下左(0..3)。全边同型同色且宽度一致 → 整框圆角描边;
        // 异型逐边画(细边画线,粗边矩形填充;dotted/dashed 虚线;double 双线;立体样式两色模拟)。
        // 跨页延续缘(组在相邻页继续)不画横向边框,左右边照画
        val edges = style.edges
        if (edges.size == 4) {
            val active = edges.map { if (it.widthEm > 0f && it.style > 0) it else null }
            val (e0, e1, e2, e3) = active
            val uniform = !box.topOpen && !box.bottomOpen &&
                e0 != null && e0 == e1 && e1 == e2 && e2 == e3
            if (uniform) {
                if (ellipseMode) {
                    boxPaint.style = Paint.Style.STROKE
                    boxPaint.strokeWidth = e0!!.widthEm * fontPx
                    boxPaint.color = adaptColor(e0.color, night).toInt()
                    canvas.drawOval(rect, boxPaint)
                } else {
                    drawUniformBorder(canvas, rect, radius, e0!!, adaptColor(e0.color, night), fontPx)
                }
            } else if (ellipseMode) {
                // 椭圆模式部分边框: 存在的边对应椭圆象限弧(右=0..90 下=90..180 左=180..270 上=270..360),
                // 相邻存在边合并为连续弧段,弧端悬空于边中点(气泡轮廓形态)
                val oval = android.graphics.RectF(rect)
                val on = active.map { it != null }
                var i = 0
                while (i < 4 && !on[i]) i++
                if (i < 4) {
                    var j = i
                    while (j < 3 && on[j + 1]) j++
                    val edgeSel = active[i] ?: active[j]
                    boxPaint.style = Paint.Style.STROKE
                    boxPaint.strokeWidth = edgeSel!!.widthEm * fontPx
                    boxPaint.color = adaptColor(edgeSel.color, night).toInt()
                    // 椭圆角度: 0°=右中, 顺时针;边1(右)=0..90, 边2(下)=90..180, 边3(左)=180..270, 边0(上)=270..360
                    val start = when (i) { 1 -> 0f; 2 -> 90f; 3 -> 180f; else -> 270f }
                    val end = when (j) { 1 -> 90f; 2 -> 180f; 3 -> 270f; else -> 360f }
                    canvas.drawArc(oval, start, end - start, false, boxPaint)
                }
            } else if (radius != null) {
                // 圆角 + 部分边框: 沿圆角矩形轮廓描存在的边(相邻两边齐全的角画弧,段首尾为尖角)
                drawPartialRoundBorder(canvas, rect, radius, listOf(e0, e1, e2, e3), night, fontPx)
            } else {
                if (!box.topOpen) drawEdge(canvas, rect, 0, e0, night, fontPx)     // 上
                if (!box.bottomOpen) drawEdge(canvas, rect, 2, e2, night, fontPx)  // 下
                drawEdge(canvas, rect, 3, e3, night, fontPx)   // 左
                drawEdge(canvas, rect, 1, e1, night, fontPx)   // 右
            }
        }
        if (rotated) canvas.restore()
    }

    private fun Paint.shadowLayerCompatClear() { clearShadowLayer() }

    private fun fillRound(canvas: Canvas, rect: android.graphics.RectF, radius: EllipseRadius?, paint: Paint) {
        when {
            radius == null || radius.rx <= 0f || radius.ry <= 0f -> canvas.drawRect(rect, paint)
            // 椭圆模式: 角弧达到半边长,轮廓即椭圆
            radius.rx >= rect.width() / 2f - 0.5f || radius.ry >= rect.height() / 2f - 0.5f ->
                canvas.drawOval(rect, paint)
            else -> canvas.drawPath(roundOutline(rect, radius.rx, radius.ry), paint)
        }
    }

    private fun strokeOutline(canvas: Canvas, outline: android.graphics.Path, w: Float, color: Int) {
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = w
        boxPaint.color = color
        canvas.drawPath(outline, boxPaint)
    }

    private fun strokeOutlineDashed(canvas: Canvas, outline: android.graphics.Path, w: Float, color: Int, dash: Float) {
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = w
        boxPaint.color = color
        boxPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dash, dash), 0f)
        canvas.drawPath(outline, boxPaint)
        boxPaint.pathEffect = null
    }

    // 整框描边(四边同型): solid 一次 stroke;dotted/dashed 虚线;double 双线;立体两色双描。
    // widthEm 以 em 计,×字号得 px(与书内 em 排版体系一致,随阅读字号缩放)
    private fun drawUniformBorder(
        canvas: Canvas,
        rect: android.graphics.RectF,
        radius: EllipseRadius?,
        edge: com.yukino.tool.module.reader.common.EdgeStyle,
        color: Long,
        fontPx: Float
    ) {
        val w = edge.widthEm * fontPx
        if (w <= 0f) return
        val outline = radius?.let { roundOutline(rect, it.rx, it.ry) }
        when (edge.style) {
            1 -> strokeOutline(canvas, outline ?: roundOutline(rect, 0f, 0f), w, color.toInt())
            2, 3 -> {
                val dash = if (edge.style == 2) w else w * 3f
                strokeOutlineDashed(canvas, outline ?: roundOutline(rect, 0f, 0f), w, color.toInt(), dash)
            }
            4 -> {
                val lw = w / 3f
                strokeOutline(canvas, outline ?: roundOutline(rect, 0f, 0f), lw, color.toInt())
                val r2 = RectInflater.inset(rect, lw * 2f)
                val ir = radius?.let { EllipseRadius((it.rx - lw * 2f).coerceAtLeast(0f), (it.ry - lw * 2f).coerceAtLeast(0f)) }
                strokeOutline(canvas, ir?.let { roundOutline(rect, it.rx, it.ry) } ?: roundOutline(r2, 0f, 0f), lw, color.toInt())
            }
            else -> {   // 5..8 立体: ridge(5)/outset(8) 上左亮;groove(6)/inset(7) 反之
                val lightTopLeft = edge.style == 5 || edge.style == 8
                val light = shade(color, lightTopLeft)
                val dark = shade(color, !lightTopLeft)
                strokeOutline(canvas, outline ?: roundOutline(rect, 0f, 0f), w / 2f, light.toInt())
                val r2 = RectInflater.inset(rect, w / 2f)
                val ir = radius?.let { EllipseRadius((it.rx - w / 2f).coerceAtLeast(0f), (it.ry - w / 2f).coerceAtLeast(0f)) }
                strokeOutline(canvas, ir?.let { roundOutline(rect, it.rx, it.ry) } ?: roundOutline(r2, 0f, 0f), w / 2f, dark.toInt())
            }
        }
    }

    // 圆角 + 部分边框: 沿圆角矩形轮廓只描存在的边。连续存在的边组成一段路径,
    // 段内相邻角画椭圆弧衔接,段首尾(邻边缺失的角)以尖角收尾;四边齐全走 uniform。
    // 样式统一按实线描边(dotted/double 的圆角组合罕见,不做混合)
    private fun drawPartialRoundBorder(
        canvas: Canvas,
        rect: android.graphics.RectF,
        radius: EllipseRadius?,
        edges: List<com.yukino.tool.module.reader.common.EdgeStyle?>,
        night: Boolean,
        fontPx: Float
    ) {
        val present = edges.map { it != null && it.widthEm > 0f && it.style > 0 }
        if (present.none { it }) return
        val rx = (radius?.rx ?: 0f).coerceAtMost(rect.width() / 2f)
        val ry = (radius?.ry ?: 0f).coerceAtMost(rect.height() / 2f)
        // 画布绝对坐标: 所有路径点以 rect 左上角为原点折算
        val L = rect.left
        val T = rect.top
        val w = rect.width()
        val h = rect.height()
        // 顺时针边 k(0上1右2下3左)的直线段与前角弧几何(前角 = 边 k-1 与 k 的夹角);
        // 角弧为椭圆弧(水平半径 rx/垂直半径 ry)
        data class Seg(val sx: Float, val sy: Float, val arc: android.graphics.RectF, val arcStart: Float)
        val segs = listOf(
            Seg(L + rx, T, android.graphics.RectF(L, T, L + 2 * rx, T + 2 * ry), 180f),                                   // 上: 前角 TL
            Seg(L + w, T + ry, android.graphics.RectF(L + w - 2 * rx, T, L + w, T + 2 * ry), 270f),   // 右: 前角 TR
            Seg(L + w - rx, T + h, android.graphics.RectF(L + w - 2 * rx, T + h - 2 * ry, L + w, T + h), 0f), // 下: 前角 BR
            Seg(L, T + h - ry, android.graphics.RectF(L, T + h - 2 * ry, L + 2 * rx, T + h), 90f)   // 左: 前角 BL
        )
        // 边 k 终点: 下一边存在缩进角半径(弧衔接),不存在画到角点(尖角)
        fun endX(k: Int, next: Boolean) = L + when (k) {
            0 -> if (next) w - rx else w
            1 -> w
            2 -> if (next) rx else 0f
            else -> 0f
        }
        fun endY(k: Int, next: Boolean) = T + when (k) {
            0 -> 0f
            1 -> if (next) h - ry else h
            2 -> h
            else -> if (next) ry else 0f
        }
        val order = (0..3).filter { present[it] }
        // 环上按连续性分段(3 与 0 视为相邻)
        val runs = ArrayList<List<Int>>()
        var run = ArrayList<Int>()
        for (i in order.indices) {
            val k = order[i]
            val prev = order.getOrNull(i - 1)
            if (prev != null && k == prev + 1) run.add(k)
            else {
                if (run.isNotEmpty()) runs.add(run)
                run = arrayListOf(k)
            }
        }
        if (run.isNotEmpty()) runs.add(run)
        // 环回绕: 末段尾边=3 且首段首边=0 → 合并(全环由 4 边齐全之外的三边+一边构成)
        if (runs.size > 1 && runs.last().last() == 3 && runs.first().first() == 0) {
            val first = runs.removeAt(0)
            val last = runs.removeAt(runs.size - 1)
            runs.add(last + first)
        }
        for (seg in runs) {
            val edge0 = edges[seg.first()]!!
            val color = adaptColor(edge0.color, night)
            if (seg.size == 4) {
                // 全环(四边齐但样式不一): 整体圆角描边,取第一条边样式
                strokeOutline(canvas, roundOutline(rect, rx, ry), edge0.widthEm * fontPx, color.toInt())
                continue
            }
            val path = android.graphics.Path()
            var started = false
            for ((j, k) in seg.withIndex()) {
                val s = segs[k]
                if (!started) {
                    // 段首: 前角是尖角(前边缺失),从角点起笔
                    path.moveTo(L + if (k == 1 || k == 2) w else 0f, T + if (k == 2 || k == 3) h else 0f)
                    started = true
                } else {
                    // 段内非首边: 前角两侧边都存在,画角弧衔接
                    path.arcTo(s.arc, s.arcStart, 90f)
                }
                val next = j < seg.size - 1
                path.lineTo(endX(k, next), endY(k, next))
            }
            boxPaint.style = Paint.Style.STROKE
            boxPaint.strokeWidth = edge0.widthEm * fontPx
            boxPaint.strokeJoin = Paint.Join.ROUND
            boxPaint.color = color.toInt()
            canvas.drawPath(path, boxPaint)
        }
    }

    // 单边绘制(side: 0上 1右 2下 3左)。跨页延续缘(topOpen/bottomOpen)由调用方传 null 边
    private fun drawEdge(
        canvas: Canvas,
        rect: android.graphics.RectF,
        side: Int,
        edge: com.yukino.tool.module.reader.common.EdgeStyle?,
        night: Boolean,
        fontPx: Float
    ) {
        if (edge == null) return
        val w = edge.widthEm * fontPx
        if (w <= 0f) return
        val color = adaptColor(edge.color, night)
        val horizontal = side == 0 || side == 2
        // 线中心: 细边贴外沿中线;粗边(>2.5px)矩形填充从外沿向内
        val y = when (side) { 0 -> rect.top; else -> rect.bottom }
        val x = when (side) { 3 -> rect.left; else -> rect.right }
        when (edge.style) {
            1 -> {
                if (horizontal) canvas.drawRect(rect.left, y, rect.right, y + if (side == 0) w else -w, boxPaint.also { it.style = Paint.Style.FILL; it.color = color.toInt() })
                else canvas.drawRect(x, rect.top, x + if (side == 3) w else -w, rect.bottom, boxPaint.also { it.style = Paint.Style.FILL; it.color = color.toInt() })
            }
            2, 3 -> {
                boxPaint.style = Paint.Style.STROKE
                boxPaint.strokeWidth = w
                boxPaint.color = color.toInt()
                val dash = if (edge.style == 2) w else w * 3f
                boxPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dash, dash), 0f)
                val mid = w / 2f
                if (horizontal) {
                    val yy = if (side == 0) y + mid else y - mid
                    canvas.drawLine(rect.left, yy, rect.right, yy, boxPaint)
                } else {
                    val xx = if (side == 3) x + mid else x - mid
                    canvas.drawLine(xx, rect.top, xx, rect.bottom, boxPaint)
                }
                boxPaint.pathEffect = null
            }
            4 -> {
                boxPaint.style = Paint.Style.FILL
                boxPaint.color = color.toInt()
                val lw = w / 3f
                if (horizontal) {
                    val dir = if (side == 0) 1f else -1f
                    canvas.drawRect(rect.left, y, rect.right, y + dir * lw, boxPaint)
                    canvas.drawRect(rect.left, y + dir * lw * 2f, rect.right, y + dir * lw * 3f, boxPaint)
                } else {
                    val dir = if (side == 3) 1f else -1f
                    canvas.drawRect(x, rect.top, x + dir * lw, rect.bottom, boxPaint)
                    canvas.drawRect(x + dir * lw * 2f, rect.top, x + dir * lw * 3f, rect.bottom, boxPaint)
                }
            }
            else -> {   // 立体样式: 单边一色(与相邻边明暗相反)——ridge/outset 上左亮,下右暗;groove/inset 反转
                val lightTopLeft = when (edge.style) {
                    5, 8 -> side == 0 || side == 3
                    6, 7 -> side == 1 || side == 2
                    else -> true
                }
                boxPaint.style = Paint.Style.FILL
                boxPaint.color = adaptColor(shade(edge.color, lightTopLeft), night).toInt()
                if (horizontal) {
                    val dir = if (side == 0) 1f else -1f
                    canvas.drawRect(rect.left, y, rect.right, y + dir * w, boxPaint)
                } else {
                    val dir = if (side == 3) 1f else -1f
                    canvas.drawRect(x, rect.top, x + dir * w, rect.bottom, boxPaint)
                }
            }
        }
    }

    private object RectInflater {
        fun inset(r: android.graphics.RectF, by: Float): android.graphics.RectF =
            android.graphics.RectF(r.left + by, r.top + by, r.right - by, r.bottom - by)
    }

    // ---------- 批次四: 表格绘制 ----------

    // 表格片段: 底色 → 格边框(collapse 相邻共享边重复覆盖;分离模式格矩形收缩 gap/2) → 格文本。
    // 文本行复用 drawStyledLine(构造行内样式段,th 表头补加粗位);垂直对齐/水平对齐由布局期算好
    private fun drawTable(canvas: Canvas, dt: DrawTable, t: ResolvedTypography) {
        val tl = dt.layout
        val night = t.night

        fun cellRect(cb: TableCellBox): android.graphics.RectF {
            val inset = if (tl.gap > 0f) tl.gap / 2f else 0f
            return android.graphics.RectF(
                dt.x + cb.x - inset, dt.y + cb.y - inset,
                dt.x + cb.x + cb.w + inset, dt.y + cb.y + cb.h + inset
            )
        }

        // 1. 底色
        for (cb in tl.cells) {
            cb.cell.bg?.let { c ->
                boxPaint.style = Paint.Style.FILL
                boxPaint.color = adaptColor(c, night).toInt()
                canvas.drawRect(cellRect(cb), boxPaint)
            }
        }
        // 2. 格边框(四边独立;0 宽/无边跳过)
        for (cb in tl.cells) {
            val rect = cellRect(cb)
            val edges = cb.cell.edges
            for ((side, edge) in edges.withIndex()) {
                if (edge.widthEm <= 0f || edge.style <= 0) continue
                drawEdge(canvas, rect, side, edge, night, t.fontPx)
            }
        }
        // 2.5 格内图片(布局期定好尺寸,内容宽内水平居中,画在格内容顶;未解码先占位,异步解码后重绘)
        for (cb in tl.cells) {
            val img = cb.img ?: continue
            val ref = cb.cell.imgRef ?: continue
            drawBitmapFit(
                canvas, ref,
                dt.x + cb.x + (cb.w - img.width) / 2f, dt.y + cb.y, img.width, img.height
            )
        }
        // 3. 格文本(逐行;水平对齐按自然行宽;表头补加粗;复用 drawStyledLine)
        for (cb in tl.cells) {
            val imgTop = cb.img?.height ?: 0f   // 文本行画在格内图片之下
            for ((li, line) in cb.lines.withIndex()) {
                val bold = if (cb.cell.header) RunStyle.BOLD else 0
                val styles = when {
                    line.styles.isEmpty() && bold != 0 ->
                        listOf(LineStyle(0, line.text.length, bold))
                    bold != 0 -> line.styles.map { LineStyle(it.start, it.end, it.style or bold, it.sizeEm, it.color, it.shadow, it.font) }
                    else -> line.styles
                }
                val base = bodyPaint
                // 行宽: 有样式段按样式 paint 逐段量,否则整行量
                var lw = 0f
                if (styles.isEmpty()) {
                    lw = base.measureText(line.text)
                } else {
                    var cursor = 0
                    for (st in styles) {
                        val a = st.start.coerceIn(0, line.text.length)
                        val b = st.end.coerceIn(0, line.text.length)
                        if (a > cursor) lw += base.measureText(line.text, cursor, a)
                        if (b > a) lw += stylePaintFor(base, false, st).measureText(line.text, a, b)
                        cursor = maxOf(cursor, b)
                    }
                    if (cursor < line.text.length) lw += base.measureText(line.text, cursor, line.text.length)
                }
                val xOff = when (cb.cell.align) {
                    1 -> (cb.w - lw) / 2f
                    2 -> (cb.w - lw).coerceAtLeast(0f)
                    else -> 0f
                }
                val lineY = dt.y + cb.y + imgTop + li * tl.lineH + tl.lineH * 0.82f
                if (styles.isEmpty()) {
                    canvas.drawText(line.text, dt.x + cb.x + xOff, lineY, base)
                } else {
                    drawStyledLine(
                        canvas,
                        DrawLine(line.text, dt.x + cb.x + xOff, lineY, false, 0L, null, styles),
                        base
                    )
                }
            }
        }
    }

    // 图片行: baseline 字段复用为行顶 y,绘制按物化尺寸;未解码先画占位框(异步解码完成后重绘)
    private fun drawImageLine(canvas: Canvas, ln: DrawLine) {
        val w = ln.imageWidth
        val h = ln.imageHeight
        if (w <= 0f || h <= 0f) return
        val bmp = imageFor(ln.imageRef ?: return, w.toInt(), h.toInt())
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, ln.baseline, w, ln.baseline + h), imagePaint)
        } else {
            drawPlaceholder(canvas, 0f, ln.baseline, w, h)
        }
    }

    // 封面/图片共用的按矩形绘制(缓存未命中画占位框)
    private fun drawBitmapFit(canvas: Canvas, ref: String, x: Float, y: Float, w: Float, h: Float) {
        val bmp = imageFor(ref, w.toInt(), h.toInt())
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, android.graphics.RectF(x, y, x + w, y + h), imagePaint)
        } else {
            drawPlaceholder(canvas, x, y, w, h)
        }
    }

    private fun drawPlaceholder(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val fg = bodyPaint.color
        decorPaint.color = (0x40 shl 24) or (fg and 0x00FFFFFF)
        decorPaint.style = Paint.Style.STROKE
        decorPaint.strokeWidth = 1.5f
        canvas.drawRoundRect(x, y, x + w, y + h, 4f, 4f, decorPaint)
        decorPaint.style = Paint.Style.FILL
    }

    // 取图片 Bitmap: 命中即回;未命中提交后台解码(单线程,重复请求去重)后重绘。
    // SVG 按目标尺寸栅格化(四期),位图直接解码
    private fun imageFor(ref: String, targetW: Int, targetH: Int): android.graphics.Bitmap? {
        synchronized(imageCache) { imageCache[ref]?.let { return it } }
        if (pendingDecodes.add(ref)) {
            imageDecoder.execute {
                val bmp = runCatching {
                    if (SvgDecoder.isSvg(ref)) SvgDecoder.decode(ref, targetW.coerceAtLeast(1), targetH.coerceAtLeast(1))
                    else android.graphics.BitmapFactory.decodeFile(ref)
                }.getOrNull()
                if (bmp != null) {
                    synchronized(imageCache) { imageCache[ref] = bmp }
                }
                pendingDecodes.remove(ref)
                postInvalidate()
            }
        }
        return null
    }

    // 换书清理(缓存图片不跨书复用,大书图片占内存)
    fun clearImages() {
        synchronized(imageCache) { imageCache.clear() }
        pendingDecodes.clear()
        invalidate()
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
        private const val IMAGE_CACHE_BYTES = 32L * 1024 * 1024   // 图片缓存内存上限(roadmap 二期)
    }
}
