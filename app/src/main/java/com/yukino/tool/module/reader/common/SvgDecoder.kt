package com.yukino.tool.module.reader.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.caverock.androidsvg.SVG
import java.io.File

// SVG 栅格化(四期;唯一新增依赖 androidsvg 1.4):
//   渲染管线对 .svg 文件按目标尺寸栅格化为 Bitmap,进既有 Bitmap LRU,绘制层无感;
//   尺寸探测供排版占位(等比换算)。解析失败返回 null(调用方走坏图占位路径)
object SvgDecoder {

    fun isSvg(path: String): Boolean = path.lowercase().endsWith(".svg")

    // 文档尺寸(px;width/height 缺省时 androidsvg 按 DPI 换算为非 0,异常返回 null)
    fun bounds(path: String): Rect? = runCatching {
        File(path).inputStream().use { stream ->
            val svg = SVG.getFromInputStream(stream)
            val w = svg.documentWidth
            val h = svg.documentHeight
            if (w > 0f && h > 0f) Rect(0, 0, w.toInt(), h.toInt()) else null
        }
    }.getOrNull()

    // 栅格化: 按目标宽度等比缩放(targetH>0 时同时约束不超高)
    fun decode(path: String, targetW: Int, targetH: Int): Bitmap? = runCatching {
        val svg = File(path).inputStream().use { stream -> SVG.getFromInputStream(stream) }
        val dw = svg.documentWidth
        val dh = svg.documentHeight
        if (dw <= 0f || dh <= 0f) return@runCatching null
        val scale = minOf(targetW / dw, if (targetH > 0) targetH / dh else targetW / dw)
        val w = (dw * scale).toInt().coerceAtLeast(1)
        val h = (dh * scale).toInt().coerceAtLeast(1)
        svg.setDocumentWidth(w.toFloat())
        svg.setDocumentHeight(h.toFloat())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp))
        bmp
    }.getOrNull()
}
