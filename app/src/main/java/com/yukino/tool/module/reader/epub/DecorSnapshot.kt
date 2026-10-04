package com.yukino.tool.module.reader.epub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.ceil

// 装饰章快照生成: 离屏 WebView 渲染原书 xhtml(css/字体相对引用经拦截器映射到解压目录,
// 禁 JS 纯 CSS 渲染),整页截为位图 → WebP 落盘(书目录 deco/ch_NNNN.webp)。
// 书内容导入后不可变,快照一次生成随书缓存;失败记入进程内黑名单,下次打开重试。
// 页面在阅读器里与正文同管线绘制(整页图块),生成未就绪前以近似排版占位。

private const val DECOR_HOST = "book.local"

object DecorSnapshot {

    // onPageFinished 后的渲染沉淀(字体/图片就位;禁 JS 无就绪回调,固定短延时)
    private const val SETTLE_MS = 400L
    private const val WEBP_QUALITY = 88

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val failed = HashSet<String>()
    private val inFlight = HashSet<String>()

    // 快照缺失时补生成(主线程调度;重复调用与已失败章幂等跳过)。
    // 成功完成回调 onDone(主线程),调用方借此重物化当前页/邻页换上真身
    fun ensure(context: Context, bookId: String, chapterIndex: Int, onDone: () -> Unit = {}) {
        if (EpubImporter.decoFile(context, bookId, chapterIndex).exists()) return
        val key = "$bookId/$chapterIndex"
        if (key in failed || !inFlight.add(key)) return
        val app = context.applicationContext
        scope.launch {
            val ok = runCatching { capture(app, bookId, chapterIndex) }.getOrDefault(false)
            inFlight.remove(key)
            if (ok) onDone() else failed.add(key)
        }
    }

    private suspend fun capture(app: Context, bookId: String, chapterIndex: Int): Boolean {
        // 源文档反查(逐 spine 提取匹配)是磁盘 IO,后台线程做
        val info = withContext(Dispatchers.Default) {
            runCatching { EpubImporter.findDecorativeSourceDoc(app, bookId, chapterIndex) }.getOrNull()
        } ?: return false
        val (doc, root) = info
        val url = "https://$DECOR_HOST/" + doc.relativeTo(root).invariantSeparatorsPath
        val out = EpubImporter.decoFile(app, bookId, chapterIndex)
        val w = app.resources.displayMetrics.widthPixels
        val screenH = app.resources.displayMetrics.heightPixels
        val done = CompletableDeferred<Boolean>()
        Handler(Looper.getMainLooper()).post {
            var view: WebView? = null
            try {
                view = WebView(app).apply {
                    settings.javaScriptEnabled = false          // 装饰页无脚本,纯 CSS 渲染
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.textZoom = 100                     // 固定原书设计字号
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    isVerticalScrollBarEnabled = false
                    setBackgroundColor(Color.WHITE)
                }
                val webView = view
                webView.webViewClient = object : WebViewClient() {
                    // 本机虚拟域映射到解压目录;外部请求一律空响应(离线且防书内外链)。
                    // xhtml 注入防御性 CSS: 图片限宽视口(原书图常带自然尺寸,溢出视口会被截)
                    override fun shouldInterceptRequest(
                        v: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val u = request.url
                        if (u.host != DECOR_HOST) return emptyResponse()
                        val rel = u.path?.trimStart('/') ?: return emptyResponse()
                        val f = File(root, rel)
                        if (!f.canonicalFile.path.startsWith(root.canonicalFile.path) || !f.isFile) {
                            return emptyResponse()
                        }
                        return try {
                            if (f.extension.lowercase() in HTML_EXT) decorateHtml(f)
                            else WebResourceResponse(mimeOf(f), mimeCharset(f), f.inputStream())
                        } catch (e: Exception) {
                            emptyResponse()
                        }
                    }

                    override fun onPageFinished(v: WebView, url: String) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            settle(webView, w, out, done)
                        }, SETTLE_MS)
                    }
                }
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, w, screenH)
                webView.loadUrl(url)
            } catch (t: Throwable) {
                view?.destroy()
                done.complete(false)
            }
        }
        return done.await()
    }

    // 渲染沉淀后量内容高(高度超屏时重布局到全内容高再截),整页截位图
    private fun settle(view: WebView, w: Int, out: File, done: CompletableDeferred<Boolean>) {
        try {
            val contentH = ceil(view.contentHeight * view.scale).toInt()
            val h = if (contentH > 0) contentH else view.height
            if (w <= 0 || h <= 0) {
                view.destroy()
                done.complete(false)
                return
            }
            view.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            view.destroy()
            // 全同色 = 渲染未完成(白板),不落盘防白图永久缓存
            if (isUniform(bmp)) {
                bmp.recycle()
                done.complete(false)
                return
            }
            // 裁掉底部空白(body margin/占位元素留下的整幅背景色),页面即内容
            val cropped = cropBottomBlank(bmp)
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { cropped.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, it) }
                }.getOrDefault(false)
                bmp.recycle()
                done.complete(ok)
            }
        } catch (t: Throwable) {
            runCatching { view.destroy() }
            done.complete(false)
        }
    }

    // 网格抽样: 所有采样点同色 → 视为空白渲染
    private fun isUniform(bmp: Bitmap): Boolean {
        val first = bmp.getPixel(0, 0)
        val stepX = maxOf(1, bmp.width / 24)
        val stepY = maxOf(1, bmp.height / 24)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getPixel(x, y) != first) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    // 底部空白裁剪: 背景色取左上角像素,自底向上找最后一个"存在非背景采样"的行。
    // 全背景(理论不可达,isUniform 已拦)返回原图
    private fun cropBottomBlank(bmp: Bitmap): Bitmap {
        val bg = bmp.getPixel(0, 0)
        val stepX = maxOf(1, bmp.width / 48)
        var lastContent = -1
        var y = bmp.height - 1
        while (y >= 0 && lastContent < 0) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getPixel(x, y) != bg) {
                    lastContent = y
                    break
                }
                x += stepX
            }
            y--
        }
        if (lastContent < 0 || lastContent >= bmp.height - 1) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, lastContent + 1)
    }

    private fun emptyResponse() =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private val HTML_EXT = setOf("xhtml", "html", "htm")

    // 注入防御性 CSS(插在 </head> 前,书内 CSS 可按序覆盖):
    // 图片/矢量限宽视口——转制书插图常带自然尺寸(超 CSS 视口),不限宽会横向溢出被截
    private fun decorateHtml(f: File): WebResourceResponse {
        val text = f.readText()
        val style = "<style>img,svg{max-width:100%;height:auto}</style>"
        val injected = if (text.contains("</head>", ignoreCase = true)) {
            text.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$style</head>")
        } else {
            text + style
        }
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injected.toByteArray()))
    }

    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "xhtml", "html", "htm" -> "text/html"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun mimeCharset(f: File) = when (f.extension.lowercase()) {
        "xhtml", "html", "htm", "css", "svg" -> "utf-8"
        else -> null
    }
}
