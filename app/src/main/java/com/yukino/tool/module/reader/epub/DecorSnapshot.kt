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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.ceil

// 装饰章快照生成: 离屏 WebView 渲染原书 xhtml(css/字体相对引用经拦截器映射到解压目录,
// 禁 JS 纯 CSS 渲染),整页截为位图 → WebP 落盘(书目录 deco/ch_NNNN.webp)。
// 书内容导入后不可变,快照一次生成随书缓存;失败记入进程内黑名单,下次打开重试。
// 页面在阅读器里与正文同管线绘制(整页图块),生成未就绪前以近似排版占位。

private const val DECOR_HOST = "book.local"

// 注入防御 CSS: 仅图片/矢量限宽视口(静态第一道防线, 无布局副作用)。
// 文字容器的溢出不在此处理——由就绪探测脚本精准收缩(只打补丁溢出元素, 正常元素零干扰)
private const val ADAPTIVE_CSS =
    "<style>img,svg{max-width:100%;height:auto}</style>"

// 注入就绪探测: onload(全部资源含图片)+ fonts.ready(@font-face 字体, 避免 FOIT 空白)
// → 遍历收缩溢出视口的元素(border-box+max-width, 只打补丁溢出元素, 页面自适应一次成型)
// → requestIdleCallback(渲染消化完的空闲点)→ 桥上报 scrollWidth;页内 4s 硬超时兜底
private const val READY_SCRIPT_PREFIX =
    "<script>window.__decoReady=false;" +
        "(function(){function fit(){var vw=document.documentElement.clientWidth;" +
        "var els=document.querySelectorAll('body *');" +
        "for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();" +
        "if(r.right>vw+1){els[i].style.maxWidth='100%';els[i].style.boxSizing='border-box';}}}" +
        "function done(){if(window.__decoReady)return;fit();window.__decoReady=true;" +
        "__decoBridge.onReady(document.documentElement.scrollWidth);}" +
        "var loaded=new Promise(function(res){if(document.readyState==='complete')res();" +
        "else window.addEventListener('load',function(){res();});});" +
        "var fonts=document.fonts?document.fonts.ready:Promise.resolve();" +
        "Promise.all([loaded,fonts]).then(function(){" +
        "if(window.requestIdleCallback)requestIdleCallback(done,{timeout:500});else setTimeout(done,0);});" +
        "setTimeout(done,4000);})();</script>"

// 剥书内脚本(JS 引擎只为探测脚本服务, 书内代码无源可跑)
internal fun stripScripts(html: String): String = html
    .replace(Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
    .replace(Regex("<script\\b[^>]*/>", RegexOption.IGNORE_CASE), "")

// 剥书内脚本 + 注入自适应 CSS 与就绪探测(插在 </head> 前, 书内 CSS 可按序覆盖)
internal fun injectInto(html: String): String {
    val text = stripScripts(html)
    val head = ADAPTIVE_CSS + READY_SCRIPT_PREFIX
    return if (text.contains("</head>", ignoreCase = true)) {
        text.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$head</head>")
    } else {
        text + head
    }
}

object DecorSnapshot {

    // 桥未回调时的兜底死线(从 loadUrl 起计时, 覆盖 load 挂死/桥丢失/JS 异常等一切路径;
    // 正常页面页内脚本 4s 自兜底会先经桥回调, 12s 只是死线), 触发按现状截, 不无限等
    private const val FALLBACK_MS = 12000L
    // 就绪回调后的绘制沉淀(fonts.ready 触发的重排重绘消化)
    private const val PAINT_SETTLE_MS = 100L
    private const val WEBP_QUALITY = 88

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val failed = HashSet<String>()
    private val inFlight = HashSet<String>()

    // 串行闸: 离屏 WebView 并发渲染会击穿渲染内存(Chromium tile limits exceeded,
    // 内容不绘制→半成品快照), 同一时刻只允许一个 WebView 渲染
    private val gate = kotlinx.coroutines.sync.Mutex(false)

    // 快照缺失时补生成(主线程调度;重复调用与已失败章幂等跳过)。
    // 成功完成回调 onDone(主线程),调用方借此重物化当前页/邻页换上真身
    fun ensure(context: Context, bookId: String, chapterIndex: Int, onDone: () -> Unit = {}) {
        if (EpubImporter.decoFile(context, bookId, chapterIndex).exists()) return
        val key = "$bookId/$chapterIndex"
        if (key in failed || !inFlight.add(key)) return
        val app = context.applicationContext
        scope.launch {
            val ok = gate.withLock {
                runCatching { capture(app, bookId, chapterIndex) }.getOrDefault(false)
            }
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
                    // JS 引擎只为注入的就绪探测脚本服务(书内 <script> 已在拦截器剥除)
                    settings.javaScriptEnabled = true
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
                // 就绪桥: 探测脚本在 onload+fonts.ready+idle 后回调(JavaBridge 线程, 切主线程),
                // 就绪后的沉淀延时让字体重排重绘消化完再截;主线程 5s 兜底防桥丢失
                val handled = java.util.concurrent.atomic.AtomicBoolean(false)
                val fallback = Runnable {
                    if (handled.compareAndSet(false, true)) settle(webView, w, out, done)
                }
                Handler(Looper.getMainLooper()).postDelayed(fallback, FALLBACK_MS)
                webView.addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun onReady(scrollWidth: Int) {
                        Handler(Looper.getMainLooper()).post {
                            if (handled.compareAndSet(false, true)) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    settle(webView, w, out, done)
                                }, PAINT_SETTLE_MS)
                            }
                        }
                    }
                }, "__decoBridge")
                webView.webViewClient = object : WebViewClient() {
                    // 本机虚拟域映射到解压目录;外部请求一律空响应(离线且防书内外链)。
                    // xhtml 注入自适应 CSS 与就绪探测, 并剥书内脚本
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
                        // 就绪信号由页内探测脚本经桥上报(主路径);兜底死线已在 loadUrl 前挂上
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

    private fun decorateHtml(f: File): WebResourceResponse =
        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injectInto(f.readText()).toByteArray()))

    private val HTML_EXT = setOf("xhtml", "html", "htm")

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
