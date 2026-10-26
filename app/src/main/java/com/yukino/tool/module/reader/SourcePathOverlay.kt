package com.yukino.tool.module.reader

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

// 源文件路径悬浮条: 授权失效重新选文件期间, 悬浮于系统文件选择器上层展示原文件绝对路径,
// 指引用户在选择器中定位同一个文件。顶部居中、单行显示(超宽部分横滑查看), 右侧 ✕ 可
// 随时关闭(仅移除悬浮条, 选择器保持打开); 不抢焦点不影响选择器返回键; 条以外区域触摸
// 穿透给选择器。由调用方在拉起选择器前 show、选择器返回后 hide(取消/选中都移除)。
object SourcePathOverlay {

    private var view: LinearLayout? = null

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
            setPadding(dp(app, 14), dp(app, 10), dp(app, 6), dp(app, 10))
            setOnClickListener { hide(app) }
        }
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(app, 18), dp(app, 10), dp(app, 8), dp(app, 10))
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
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // 可触摸(横滑/关闭), 不抢焦点
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight(app) + dp(app, 8)   // 顶部避开状态栏
        }
        runCatching { wm.addView(row, params) }.onFailure { return }
        view = row
    }

    fun hide(context: Context) {
        val v = view ?: return
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        view = null
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
