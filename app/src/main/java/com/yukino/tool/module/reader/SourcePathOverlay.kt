package com.yukino.tool.module.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

// 源文件路径悬浮条: 授权失效重新选文件期间, 悬浮于系统文件选择器上层展示原文件绝对路径,
// 指引用户在选择器中定位同一个文件。顶部居中、单行显示(超宽部分横滑查看), 右侧 ✕ 可
// 随时关闭(仅移除悬浮条, 选择器保持打开); 按住上下拖动可调整纵向位置(水平居中不变,
// 位置进程内记住, 下次拉起沿用, 默认状态栏下方); 不抢焦点不影响选择器返回键; 条以外
// 区域触摸穿透给选择器。由调用方在拉起选择器前 show、选择器返回后 hide(取消/选中都移除)。
object SourcePathOverlay {

    private var attached: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    // 用户拖到的纵向位置(屏幕坐标 y), 进程内记住
    private var lastY: Int? = null

    fun show(context: Context, path: String) {
        hide(context)
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val pathText = TextView(app).apply {
            text = path
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true   // 不折行不省略, 超宽部分靠 HorizontalScrollView 滑动
        }
        val scroller = HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                pathText,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val close = TextView(app).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            // 竖向零内边距+垂直居中: 避免按钮自身行高把整条撑高产生大片不可滚动的死区
            setPadding(dp(app, 14), 0, dp(app, 6), 0)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { hide(app) }
        }
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(app, 18), dp(app, 6), dp(app, 8), dp(app, 6))
            background = GradientDrawable().apply {
                setColor(0xEE2B2B30.toInt())
                cornerRadius = dp(app, 12).toFloat()
            }
            // 悬浮条宽度显式计算(weight 在 WindowManager 的 UNSPECIFIED 测量下会塌成 0):
            // 视口 = min(文本自然宽, 屏宽90%-两侧内边距-关闭钮), 条宽 = 视口+装饰宽
            pathText.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            close.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val maxW = (app.resources.displayMetrics.widthPixels * 0.9f).toInt()
            val chrome = dp(app, 18) + dp(app, 8) + close.measuredWidth
            val viewport = (pathText.measuredWidth).coerceAtMost(maxW - chrome)
            addView(scroller, LinearLayout.LayoutParams(viewport, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                close,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

        // 拖动层: 垂直位移超阈值且大于水平位移 → 拦截为窗口拖动(实时 updateViewLayout);
        // 水平移动放行给路径横滑, 轻点放行给 ✕。空白区域按下也接管(onTouchEvent 返 true),
        // 保证从条内空白处起步的拖动能继续收到事件。
        val dragLayer = object : FrameLayout(app) {
            private val slop = ViewConfiguration.get(app).scaledTouchSlop
            private var downX = 0f
            private var downY = 0f
            private var winY0 = 0
            private var dragging = false

            private fun record(ev: MotionEvent) {
                downX = ev.rawX; downY = ev.rawY
                winY0 = params?.y ?: 0
                dragging = false
            }

            private fun verticalDragStart(ev: MotionEvent): Boolean =
                !dragging && abs(ev.rawY - downY) > slop && abs(ev.rawY - downY) > abs(ev.rawX - downX)

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> record(ev)
                    MotionEvent.ACTION_MOVE -> if (verticalDragStart(ev)) {
                        dragging = true
                        return true   // 子视图收到 CANCEL, 后续事件进本层 onTouchEvent
                    }
                }
                return false
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> record(ev)
                    MotionEvent.ACTION_MOVE -> if (verticalDragStart(ev)) dragging = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
                }
                if (dragging && ev.actionMasked == MotionEvent.ACTION_MOVE) applyDrag(ev)
                return true
            }

            private fun applyDrag(ev: MotionEvent) {
                val p = params ?: return
                val screenH = app.resources.displayMetrics.heightPixels
                p.y = (winY0 + (ev.rawY - downY).toInt())
                    .coerceIn(0, (screenH - height).coerceAtLeast(0))
                lastY = p.y
                runCatching { wm.updateViewLayout(this, p) }
            }
        }
        dragLayer.addView(
            row,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // 可触摸(拖动/横滑/关闭), 不抢焦点
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = lastY ?: (statusBarHeight(app) + dp(app, 8))   // 上次拖到的位置或默认状态栏下
            // 关闭系统栏 inset 适配: 否则状态栏高度被当作窗口内边距, 条高虚胖一截、
            // 文本触控带整体偏移(横滑/拖动落点漂移)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
        params = p
        runCatching { wm.addView(dragLayer, p) }.onFailure {
            params = null; windowManager = null; return
        }
        windowManager = wm
        attached = dragLayer
    }

    fun hide(context: Context) {
        val v = attached ?: return
        val wm = windowManager
            ?: context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        attached = null
        params = null
        windowManager = null
    }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(context, 28)
    }

    private fun dp(context: Context, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
        ).toInt()
}
