package com.yukino.tool.module.reader

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

// 源文件路径悬浮条: 授权失效重新选文件期间, 悬浮于系统文件选择器上层展示原文件绝对路径,
// 指引用户在选择器中定位同一个文件。不可获焦/不可触摸, 完全不干扰选择器操作;
// 由调用方在拉起选择器前 show、选择器返回后 hide(取消/选中都移除)。
object SourcePathOverlay {

    private var view: TextView? = null

    fun show(context: Context, path: String) {
        hide(context)
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(app).apply {
            text = path
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            // 路径可能很长: 限宽换行至多 3 行, 末行截断
            maxLines = 3
            setPadding(dp(app, 18), dp(app, 10), dp(app, 18), dp(app, 10))
            background = GradientDrawable().apply {
                setColor(0xEE2B2B30.toInt())
                cornerRadius = dp(app, 12).toFloat()
            }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            horizontalMargin = 0f
            verticalMargin = 0.06f   // 距底部约屏高 6%, 避开导航栏
        }
        runCatching { wm.addView(tv, params) }.onFailure { return }
        // 限宽屏宽 88%, 防超长路径顶满屏幕
        val maxW = (app.resources.displayMetrics.widthPixels * 0.88f).toInt()
        tv.maxWidth = maxW
        view = tv
    }

    fun hide(context: Context) {
        val v = view ?: return
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        view = null
    }

    private fun dp(context: Context, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
        ).toInt()
}
