package com.yukino.tool.module.reader.epub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yukino.tool.module.reader.common.BlockCache
import com.yukino.tool.module.reader.common.BlockGeom
import com.yukino.tool.module.reader.common.CachedBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

// WEBVIEW 块渲染服务(混合渲染,docs/epub-hybrid-render-boundary.md §5/§7):
// 复用装饰章快照基建(离屏 WebView + 虚拟域拦截器),粒度从整章降到块级片段。
// mini HTML = 原文档样式(head 外链 + <style> 原文)+ 祖先壳逐层嵌套 + 块 HTML;
// 注入 html{font-size:根字号} 使块随阅读字号整体缩放;渲染稳定后 JS 量内容高并
// 逐字符采集 Range 矩形(字符几何表,选择用),截图 PNG + 几何 JSON 落盘。
// 按需生成 + 缓存:缓存键见 BlockCache(渲染器版本|字号|版心宽|块内容 hash),失效即重渲;
// 失败/超时记入进程内黑名单,排版兜底纯文本投影自绘,绝不阻塞打开书。

private const val BLK_HOST = "book.local"
private const val BLK_FALLBACK_MS = 12000L
private const val BLK_PAINT_SETTLE_MS = 100L
private const val GEOM_MAX_CHARS = 5000

// 剥书内脚本(JS 引擎只为注入的采集脚本服务, 书内代码无源可跑)
internal fun stripScripts(html: String): String = html
    .replace(Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
    .replace(Regex("<script\\b[^>]*/>", RegexOption.IGNORE_CASE), "")
// 离屏 WebView 帧产出被节流,draw 可能画到空白帧: 白板重试参数
private const val BLK_DRAW_TRIES = 3
private const val BLK_DRAW_RETRY_MS = 250L

// 块渲染入参(提取期产物,来自章文件 WEBVIEW 段 + 章级 CSS 资源)
class BlockSpec(
    val docDir: String,             // 来源文档相对解压根的目录(相对引用解析基准)
    val html: String,               // 块 HTML 片段
    val shell: String,              // 祖先壳(body→块的逐层开标签)
    val cssHrefs: List<String>,     // 原文档 head 外部样式 href 原样
    val cssInline: List<String>,    // 原文档 <style> 块原文
    val fillViewport: Boolean = false  // body 容器聚合块: html 注入 min-height 撑满版心,位图占整页
) {
    fun contentHash(): String = BlockCache.contentHashOf(html, shell, docDir, cssHrefs, cssInline)
}

object WebViewBlockRenderer {

    private val ioScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val inFlight = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // 绘制互斥: view.draw 触发 Chromium tile 光栅化,并发绘制会击穿 tile 内存上限
    // (历史实测白板)——加载/稳定期可流水线并发,绘制段全局串行
    private val drawLock = Any()

    // 加载流水线并发度: 同时处于加载/稳定期的离屏 WebView 数(等待字体/图片期间
    // 几乎不占 tile);过高会叠加内存与光栅化压力
    private const val RENDER_CONCURRENCY = 3

    // 流水线补齐(并发加载 + 串行绘制): N 个 worker 从队列取块并发创建/加载离屏
    // WebView(等待字体/图片期间不占 tile),页内稳定环自治;view.draw 光栅化段全局
    // 互斥(drawLock)。全部处理完(含失败)才返回;阻塞在打开书 load 路径,断行时
    // 块位图尺寸即已就绪。取消(中途退出阅读页)即中止,未落盘的块下次打开重试。
    // root = 解压根目录(chapterDir,css/字体/图片拦截映射的基准)
    // 行距注入: lineOverride=false 时 line-height 只作 body 默认(书内元素声明层叠优先),
    // true 时 !important 压过书内声明(设置"行距跟随书内"关闭)
    suspend fun ensureAllBlocking(
        context: Context,
        root: File,
        specs: List<Pair<String, BlockSpec>>,
        fontPx: Float,
        textWidth: Int,
        textHeight: Int,
        lineSpacingPercent: Int,
        lineOverride: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        val app = context.applicationContext
        val missing = specs.filter { (key, _) -> !BlockCache.inMemory(key) && !BlockCache.fileOf(root, key).exists() }
        val total = missing.size
        if (total == 0) return
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            repeat(minOf(RENDER_CONCURRENCY, total)) {
                launch(Dispatchers.Default) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val i = next.getAndIncrement()
                        if (i >= total) return@launch
                        val (key, spec) = missing[i]
                        if (key in failed || !inFlight.add(key)) {
                            onProgress(doneCount.incrementAndGet(), total); continue
                        }
                        val ok = runCatching {
                            capture(app, root, key, spec, fontPx, textWidth, textHeight, lineSpacingPercent, lineOverride)
                        }.getOrDefault(false)
                        inFlight.remove(key)
                        if (!ok) failed.add(key)
                        onProgress(doneCount.incrementAndGet(), total)
                    }
                }
            }
        }
    }

    // 块位图高度上限(物理 px,约 4 屏): 超高的块由采集脚本按比例缩小根 zoom 重排重采,
    // 防 ARGB 位图内存失控(30000px 高 ≈ 100MB)
    private fun limitPxOf(screenH: Int): Int = (screenH * 4).coerceIn(4000, 16000)

    // 生成一个块(结果入 BlockCache.mem + 落盘)
    private suspend fun capture(
        app: Context,
        root: File,
        key: String,
        spec: BlockSpec,
        fontPx: Float,
        textWidth: Int,
        textHeight: Int,
        lineSpacingPercent: Int,
        lineOverride: Boolean
    ): Boolean = withContext(Dispatchers.Default) {
        val w = textWidth.coerceAtLeast(1)
        val metrics = app.resources.displayMetrics
        val screenH = metrics.heightPixels
        val density = metrics.density
        // body 容器聚合块(fillViewport): 视口=版心高,html 注入 min-height 使白底/背景
        // 铺满整页(整页设计章不再有内容高以下的纸底分界)
        val fillVp = spec.fillViewport
        val pageH = if (fillVp) textHeight.coerceAtLeast(1) else screenH
        val html = buildMiniHtml(
            spec, fontPx, density,
            lineMultCss(lineSpacingPercent, lineOverride),
            collectJs(limitPxOf(screenH)), fillVp
        )
        val limitPx = limitPxOf(screenH)
        val done = CompletableDeferred<Boolean>()
        Handler(Looper.getMainLooper()).post {
            var view: WebView? = null
            try {
                view = WebView(app).apply {
                    settings.javaScriptEnabled = true   // JS 引擎只为注入的采集脚本服务
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.textZoom = 100
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // 钉死缩放比 = density: CSS px 即 dp,html 根字号按此换算注入,
                    // 块位图与正文同视觉比例(默认缩放比不保证,必须显式)
                    setInitialScale((density * 100).toInt())
                    isVerticalScrollBarEnabled = false
                    setBackgroundColor(Color.WHITE)
                }
                val webView = view
                val handled = java.util.concurrent.atomic.AtomicBoolean(false)
                // 兜底死线: 桥丢失/挂死时按现状截(几何空,选择退化)
                val fallback = Runnable {
                    if (handled.compareAndSet(false, true)) settle(webView, w, pageH, limitPx, fillVp, root, key, null, done)
                }
                Handler(Looper.getMainLooper()).postDelayed(fallback, BLK_FALLBACK_MS)
                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onDone(json: String) {
                        android.util.Log.d("BlkRender", "bridge onDone key=${key.take(8)} json=${json.take(120)}")
                        Handler(Looper.getMainLooper()).post {
                            if (handled.compareAndSet(false, true)) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    settle(webView, w, pageH, limitPx, fillVp, root, key, json, done)
                                }, BLK_PAINT_SETTLE_MS)
                            }
                        }
                    }
                }, "__blkBridge")
                webView.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.d("BlkJS", "key=${key.take(8)} ${message.message()}")
                        return true
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    // 虚拟域映射解压目录(与装饰章快照同款);外部请求一律空响应
                    override fun shouldInterceptRequest(
                        v: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val u = request.url
                        if (u.host != BLK_HOST) return emptyResponse()
                        // Chromium 请求路径是 percent-encoded,磁盘文件名是原始名,必须解码后再映射
                        val rel = android.net.Uri.decode(u.path?.trimStart('/') ?: return emptyResponse())
                        val f = File(root, rel)
                        if (!f.canonicalFile.path.startsWith(root.canonicalFile.path) || !f.isFile) {
                            return emptyResponse()
                        }
                        return try {
                            WebResourceResponse(mimeOf(f), mimeCharset(f), f.inputStream())
                        } catch (e: Exception) {
                            emptyResponse()
                        }
                    }
                }
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(pageH, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, w, pageH)
                val base = "https://$BLK_HOST/" + spec.docDir.let { if (it.isEmpty()) "" else "$it/" }
                webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
            } catch (t: Throwable) {
                view?.destroy()
                done.complete(false)
            }
        }
        done.await()
    }

    // mini HTML 组装: 原文档样式(外链 href 原样经 <base> 解析 + <style> 原文)+
    // html 根字号注入 + 祖先壳(body 属性并入 <body> 标签)+ 块 HTML + 壳反序闭合。
    // 书内脚本一律剥除(JS 引擎只为采集脚本服务)
    // 行距: 无单位倍数随各元素自身字号缩放(对齐自绘"字号×倍率"语义)。
    // lineMultCss 为空 = 不注入;非空时 override 模式 !important 压书内声明,否则只作 body 默认
    private fun buildMiniHtml(
        spec: BlockSpec,
        fontPx: Float,
        density: Float,
        lineMultCss: String?,
        collectScript: String,
        fillViewport: Boolean
    ): String {
        // 根字号(CSS px): 钉死缩放比 = density。
        // 普通块 = 阅读字号,块随阅读字号整体缩放;
        val rootFontPx = fontPx / density
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        for (href in spec.cssHrefs) {
            sb.append("<link rel=\"stylesheet\" href=\"").append(escapeAttr(href)).append("\">")
        }
        for (css in spec.cssInline) {
            sb.append("<style>").append(stripScripts(css)).append("</style>")
        }
        sb.append("<style>html{font-size:").append(rootFontPx).append("px}")
        sb.append("body{margin:0;padding:0}")
        if (fillViewport) sb.append("html{height:100%}body{min-height:100%}")
        sb.append("</style>")
        if (lineMultCss != null) sb.append("<style>").append(lineMultCss).append("</style>")
        sb.append(collectScript)
        sb.append("</head>")
        val shell = stripScripts(spec.shell)
        val bodyTag = if (shell.startsWith("<body")) shell.substringBefore('>') + ">" else "<body>"
        val innerShell = if (shell.startsWith("<body")) shell.substringAfter('>', "") else shell
        sb.append(bodyTag)
        sb.append(innerShell)
        sb.append(stripScripts(spec.html))
        Regex("<([A-Za-z][A-Za-z0-9]*)").findAll(innerShell).toList().reversed().forEach {
            sb.append("</").append(it.groupValues[1]).append('>')
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    // 行距注入 CSS 片段: 覆盖模式 body *{line-height:X!important}(压过书内声明);
    // 默认模式 body{line-height:X}(书内元素声明层叠自动优先,null=不注入)。
    private fun lineMultCss(lineSpacingPercent: Int, lineOverride: Boolean): String {
        val mult = "%.2f".format((lineSpacingPercent / 100f).coerceIn(0.5f, 5f))
        return if (lineOverride) "body *{line-height:${mult}!important}"
               else "body{line-height:$mult}"
    }

    private fun escapeAttr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;")

    // 渲染沉淀: 量高截图落盘 + 几何表落盘。geomJson 为 null(超时兜底)时只截不落几何
    private fun settle(
        view: WebView,
        w: Int,
        pageH: Int,
        limitPx: Int,
        fillVp: Boolean,
        root: File,
        key: String,
        geomJson: String?,
        done: CompletableDeferred<Boolean>
    ) {
        try {
            val contentH = ceil(view.contentHeight * view.scale).toInt()
            // 超高钳制: 页内 scale 已把视觉内容缩进上限,位图按布局高截,下部空白由
            // cropBottomBlank 裁除
            val h = (if (contentH > 0) contentH else view.height)
                .coerceAtMost(limitPx)
            // 内容宽: 桥上报的布局宽(物理 px)超视口时撑开视口完整截取——定宽溢出块
            // (学籍表/人物介绍聚合块等)由此完整呈现,显示层按位图比例缩放到版心
            val contentW = geomJson?.let {
                runCatching { org.json.JSONObject(it).getDouble("w").toInt() }.getOrNull()
            } ?: 0
            val layoutW = maxOf(w, contentW)
            android.util.Log.d(
                "BlkRender",
                "settle key=${key.take(8)} contentH=$contentH h=$h contentW=$contentW layoutW=$layoutW scale=${view.scale} json=${geomJson?.take(60)}"
            )
            if (layoutW <= 0 || h <= 0) {
                view.destroy()
                done.complete(false)
                return
            }
            view.layout(0, 0, layoutW, h)
            attemptDraw(view, layoutW, h, root, key, geomJson, fillVp, 0, done)
        } catch (t: Throwable) {
            runCatching { view.destroy() }
            done.complete(false)
        }
    }

    // 绘制(带白板重试): 离屏 WebView 的帧产出被节流,draw 可能画到空白帧——白板时
    // 延后重试(最多 3 次)。出帧后再在页内收割几何(与位图同源),像素抽检门兜底
    private fun attemptDraw(
        view: WebView,
        w: Int,
        h: Int,
        root: File,
        key: String,
        geomJson: String?,
        fillVp: Boolean,
        attempt: Int,
        done: CompletableDeferred<Boolean>
    ) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            // 光栅化段全局互斥(并发绘制击穿 tile 内存上限,历史实测白板)
            synchronized(drawLock) { view.draw(Canvas(bmp)) }
        } catch (t: Throwable) {
            bmp.recycle()
            runCatching { view.destroy() }
            done.complete(false)
            return
        }
        if (isUniform(bmp) && attempt + 1 < BLK_DRAW_TRIES) {
            bmp.recycle()
            android.util.Log.d("BlkRender", "blank draw retry#${attempt + 1} key=${key.take(8)}")
            Handler(Looper.getMainLooper()).postDelayed({
                attemptDraw(view, w, h, root, key, geomJson, fillVp, attempt + 1, done)
            }, BLK_DRAW_RETRY_MS)
            return
        }
        // 先绘制(绘制会强制渲染器落地最终布局/字形),再在页内收割几何——收割与
        // 位图同源;像素抽检门兜底: 矩形贴不上字形就弃用几何(显示正确优先)
        view.evaluateJavascript("(window.__blkRecollect&&window.__blkRecollect())||''") { fresh ->
            val unquoted = if (fresh.length >= 2 && fresh.startsWith("\""))
                runCatching { org.json.JSONTokener(fresh).nextValue().toString() }.getOrNull() else null
            val finalJson = unquoted?.takeIf { it.contains("\"ok\"") } ?: geomJson
            saveBlockBitmap(view, bmp, w, root, key, finalJson, fillVp, done)
        }
    }

    // 白板防御(末次仍白 = 放弃,该块本次失败走兜底自绘) + 底部裁剪 + 像素抽检门 +
    // PNG/几何落盘(view 在收割回调后在此销毁)
    private fun saveBlockBitmap(
        view: WebView,
        bmp: Bitmap,
        w: Int,
        root: File,
        key: String,
        geomJson: String?,
        fillVp: Boolean,
        done: CompletableDeferred<Boolean>
    ) {
        var cropped: Bitmap? = null
        try {
            if (isUniform(bmp)) {   // 白板 = 渲染未完成,不落盘防白图永久缓存
                bmp.recycle()
                view.destroy()
                done.complete(false)
                return
            }
            // fillViewport(整页设计块): 白底/背景铺满整页是版式的一部分,不裁底部
            cropped = if (fillVp) bmp else cropBottomBlank(bmp)
            // cropBottomBlank 无裁剪时原样返回 bmp: 别名时不可回收,否则后续压缩即崩
            if (cropped !== bmp) bmp.recycle()
            var geom = geomJson?.let { runCatching { BlockCache.parseGeomJson(it) }.getOrNull() }
            // 像素抽检门: 前 4 个非空格字符的矩形内应有字形(区域像素有明暗跨度)。
            // 采集与绘制的布局若仍有错位(离屏渲染时序深坑),宁可弃用几何(选择退化,
            // 显示永远正确),绝不带着错位坐标入库
            if (geom != null && !rectsHitInk(geom, cropped)) {
                android.util.Log.d("BlkRender", "pixel gate reject key=${key.take(8)}")
                geom = null
            }
            val finalGeom = geom
            ioScope.launch {
                val ok = runCatching {
                    File(root, BlockCache.DIR_NAME).mkdirs()
                    val out = BlockCache.fileOf(root, key)
                    out.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 0, it) }
                    if (finalGeom != null) BlockCache.geomFileOf(root, key).writeText(geomJson!!)
                    else BlockCache.geomFileOf(root, key).writeText("{\"ok\":0}")
                    BlockCache.remember(key, CachedBlock(out, cropped.width, cropped.height, finalGeom))
                    true
                }.getOrDefault(false)
                cropped.recycle()
                // WebView 只能在创建它的主线程销毁(IO 线程 destroy 会直接崩)
                Handler(Looper.getMainLooper()).post { view.destroy() }
                done.complete(ok)
            }
        } catch (t: Throwable) {
            cropped?.recycle()
            runCatching { view.destroy() }
            done.complete(false)
        }
    }

    // 像素抽检: 前 4 个非空格字符矩形内采样, 明暗跨度 > 40 视为有字形;有效采样 ≥2 个
    // 命中即通过(越界矩形不计入——横向溢出块视口外的字符本就不可见,几何仍可信)。
    // (深底白字/浅底黑字均表现为双色调跨度;空矩形区域近似单色)
    private fun rectsHitInk(geom: BlockGeom, bmp: Bitmap): Boolean {
        var checked = 0
        var hit = 0
        for (i in 0 until geom.charCount) {
            if (geom.chars[i] == ' ') continue
            if (checked >= 4) break
            val x = geom.rects[i * 4].toInt()
            val y = geom.rects[i * 4 + 1].toInt()
            val rw = geom.rects[i * 4 + 2].toInt()
            val rh = geom.rects[i * 4 + 3].toInt()
            if (rw <= 0 || rh <= 0 || x < 0 || y < 0 || x + rw > bmp.width || y + rh > bmp.height) continue
            checked++
            var lo = 255
            var hi = 0
            val sy = maxOf(1, rh / 6)
            val sx = maxOf(1, rw / 5)
            var yy = y
            while (yy < y + rh) {
                var xx = x
                while (xx < x + rw) {
                    val p = bmp.getPixel(xx, yy)
                    val lum = (p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114
                    val l = (lum / 1000).coerceIn(0, 255)
                    if (l < lo) lo = l
                    if (l > hi) hi = l
                    xx += sx
                }
                yy += sy
            }
            if (hi - lo > 40) hit++
        }
        return checked > 0 && hit >= 2
    }

    // 网格抽样: 所有采样点同色 → 视为空白渲染(与 DecorSnapshot 同款)
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

    // 底部空白裁剪(与 DecorSnapshot 同款)
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

    // ---------- 字符几何采集脚本 ----------
    // 与 HtmlTextExtractor.flattenBlockText 同一条扁平化规则(Kotlin/JS 两侧各实现一遍,
    // 对拍单测钉死): 文本节点原样;块级子元素边界与 <br> 折空格;连续空白压一;首尾不挂空格。
    // 就绪(onload + 强制加载全部声明字体 FontFace.load() + fonts.ready + idle)后进入
    // 稳定性环: 每 2×rAF 量一次内容宽高,连续两轮不变(或 40 轮上限)才继续——fonts.ready
    // resolve 时惰性启动的 @font-face 未必已应用、大图经拦截器加载慢,都会迟到重排使坐标
    // 整体过期(实测 22/22 块错位)。尺寸稳定后再做两遍完整采集(隔 2×rAF),字符矩形序列
    // 完全一致(布局确已定格,坐标与位图同源)才上报,10 轮仍抖动则上报 ok:0 自弃用;
    // 超高/超宽适配: 超宽由视口直接撑到内容宽(零布局干预);超高以 transform:scale
    // 等比缩到位图上限(绘制级,布局/断行零变化);逐字符
    // Range.getClientRects 采集(dpr 换算到位图
    // 像素域,相对 body 左上);字符数超上限只量高(几何空,选择退化);页内 6s 硬超时
    // 直接 ok:0(位图仍由 native 按现状截取,兜底不挂死)。
    // 采集脚本: limitPx=位图高度上限(物理 px)。
    private fun collectJs(limitPx: Int): String =
        "<script>window.__blkDone=false;" +
        "(function(){" +
        "var BLOCKS={p:1,div:1,section:1,article:1,blockquote:1,h1:1,h2:1,h3:1,h4:1,h5:1,h6:1," +
        "thead:1,tbody:1,tfoot:1,tr:1,td:1,th:1,dl:1,dt:1,dd:1,pre:1,aside:1,figure:1,figcaption:1," +
        "header:1,footer:1,main:1,nav:1,hr:1,center:1,form:1,address:1,caption:1,ol:1,ul:1,li:1,table:1};" +
        "var SKIPS={script:1,style:1,head:1,title:1,svg:1,link:1,meta:1,iframe:1,object:1,video:1,audio:1,canvas:1,template:1};" +
        "function isWs(ch){return ch===' '||ch==='\\t'||ch==='\\n'||ch==='\\r'||ch==='\\f'||ch==='\\u000B';}" +
        "var chars=[],rects=[],pendingSpace=false,started=false,lastRect=null;" +
        "var bodyL=0,bodyT=0,dpr=1;" +
        "function pushRect(r){return r?[(r.left-bodyL)*dpr,(r.top-bodyT)*dpr,r.width*dpr,r.height*dpr]:[0,0,0,0];}" +
        "function emitText(node){" +
        "var text=node.nodeValue;var range=document.createRange();" +
        "for(var i=0;i<text.length;i++){" +
        "var ch=text.charAt(i);" +
        "if(isWs(ch)){pendingSpace=true;continue;}" +
        "if(pendingSpace){pendingSpace=false;" +
        "if(started){chars.push(' ');rects.push(lastRect?pushRect(lastRect):[0,0,0,0]);}}" +
        "started=true;chars.push(ch);" +
        "range.setStart(node,i);range.setEnd(node,i+1);" +
        "var rl=range.getClientRects();" +
        "var r=rl.length>0?rl[0]:lastRect;" +
        "var arr=pushRect(r);rects.push(arr);lastRect=r;}}" +
        "function walk(node){" +
        "if(node.nodeType===3){emitText(node);return;}" +
        "if(node.nodeType!==1)return;" +
        "var tag=node.tagName?node.tagName.toLowerCase():'';" +
        "if(tag==='br'){pendingSpace=true;return;}" +
        "if(SKIPS[tag])return;" +
        "if(BLOCKS[tag])pendingSpace=true;" +
        "var kids=node.childNodes;" +
        "for(var i=0;i<kids.length;i++)walk(kids[i]);}" +
        "function collect(){" +
        "chars=[];rects=[];pendingSpace=false;started=false;lastRect=null;" +
        "var b=document.body.getBoundingClientRect();bodyL=b.left;bodyT=b.top;" +
        "var kids=document.body.childNodes;" +
        "for(var i=0;i<kids.length;i++)walk(kids[i]);}" +
        "function measure(){" +
        // body.scrollWidth 不计以视口为包含块的绝对定位/out-of-flow 溢出,并取 documentElement 补盲区
        "return{w:Math.max(document.body.scrollWidth,document.documentElement.scrollWidth)," +
        "h:Math.max(document.body.scrollHeight,document.documentElement.scrollHeight)};}"+
        "function ts(){return Math.round(performance.now());}" +
        "console.log('BLK parse t='+ts());" +
        "var loaded=new Promise(function(res){if(document.readyState==='complete')res();" +
        "else window.addEventListener('load',function(){res();});});" +
        "loaded.then(function(){console.log('BLK load t='+ts());});" +
        "var fonts=document.fonts?document.fonts.ready:Promise.resolve();" +
        "fonts.then(function(){console.log('BLK fonts.ready t='+ts());});" +
        "var fontLoads=Promise.resolve();" +
        "try{var fs=[];document.fonts.forEach(function(f){fs.push(f);});" +
        "console.log('BLK faces='+fs.length);" +
        "fontLoads=Promise.all(fs.map(function(f){return f.load().catch(function(){});}));}catch(e){}" +
        "fontLoads.then(function(){console.log('BLK fontLoads done t='+ts());});" +
        // load 事件不等 CSS background-image(装饰页插图/底纹常为背景图, 迟到会整体重排):
        // 扫全部元素与伪类的 computed backgroundImage, 逐 url new Image() 等加载+解码
        "var bgLoads=Promise.resolve();" +
        "try{" +
        "var us=(function(){" +
        "var found=[];" +
        "function scan(el,ps){" +
        "try{var v=getComputedStyle(el,ps).backgroundImage;" +
        "if(v&&v.indexOf('url(')>=0){" +
        "var ms=v.match(/url\\(([^)]+)\\)/g)||[];" +
        "for(var i=0;i<ms.length;i++){" +
        "var u=ms[i].slice(4,-1).replace(/^[\\x22\\x27]/,'').replace(/[\\x22\\x27]$/,'');" +
        "if(u&&found.indexOf(u)<0)found.push(u);}}}catch(e){}}" +
        "var els=document.querySelectorAll('*');" +
        "for(var i=0;i<els.length;i++){scan(els[i],'');scan(els[i],':before');scan(els[i],':after');}" +
        "return found;})();" +
        "console.log('BLK bgurls='+us.length);" +
        "if(us.length)bgLoads=Promise.all(us.map(function(u){" +
        "return new Promise(function(res){" +
        "var fin=(function(){var d=0;return function(){if(!d){d=1;res();}};})();" +
        "var im=new Image();" +
        "im.onload=fin;im.onerror=fin;" +
        "if(im.decode)im.decode().then(fin,fin);" +
        "im.src=u;" +
        "setTimeout(fin,4000);});}));}catch(e){}" +
        "bgLoads.then(function(){console.log('BLK bgLoads done t='+ts());});" +
        "var sw=0,sh=0,stable=0,rounds=0;" +
        "function settleLoop(){" +
        "if(window.__blkDone)return;" +
        "rounds++;" +
        "var m=measure();" +
        "if(m.w===sw&&m.h===sh){stable++;}else{stable=0;sw=m.w;sh=m.h;}" +
        "console.log('BLK round='+rounds+' w='+m.w+' h='+m.h+' stable='+stable+' t='+ts());" +
        "if(stable>=2||rounds>=40){doubleCollect();}" +
        "else requestAnimationFrame(function(){requestAnimationFrame(settleLoop);});}" +
        "var dcRounds=0;" +
        "function doubleCollect(){" +
        "if(window.__blkDone)return;" +
        "collect();" +
        "var a=JSON.stringify(rects);" +
        "requestAnimationFrame(function(){requestAnimationFrame(function(){" +
        "if(window.__blkDone)return;" +
        "collect();" +
        "var same=JSON.stringify(rects)===a;" +
        "console.log('BLK dc='+dcRounds+' same='+same+' t='+ts());" +
        "if(same){finish();}" +
        "else if(++dcRounds>=10){window.__blkBridge.onDone('{\"ok\":0}');}" +
        "else doubleCollect();" +
        "});});}" +
        "function finish(){" +
        "if(window.__blkDone)return;window.__blkDone=true;" +
        "dpr=window.devicePixelRatio||1;" +
        "var m=measure();" +
        // 超高防御: 位图高上限(物理 px),超出以 transform:scale 等比缩(绘制级,布局/
        // 断行零变化);html 底色补 body 背景,防缩放后右侧余量露白
        "var ph=m.h*dpr;" +
        "if(ph>" + limitPx + "){" +
        "var z=" + limitPx + "/ph;" +
        "document.body.style.transformOrigin='0 0';" +
        "document.body.style.transform='scale('+z+')';" +
        "var bg='';try{bg=getComputedStyle(document.body).backgroundColor;}catch(e){}" +
        "if(bg&&bg!=='transparent'&&bg!=='rgba(0, 0, 0, 0)')" +
        "document.documentElement.style.backgroundColor=bg;}" +
        "collect();" +
        "m=measure();" +
        "console.log('BLK finish h='+m.h+' chars='+chars.length+' t='+ts());" +
        "if(chars.length>" + GEOM_MAX_CHARS + "){window.__blkBridge.onDone('{\"ok\":0}');return;}" +
        "var json='{\"ok\":1,\"w\":'+Math.round(m.w*dpr)+',\"h\":'+Math.round(m.h*dpr)+',\"cs\":'+JSON.stringify(chars.join(''))+',\"rs\":'+JSON.stringify(rects)+'}';" +
        "window.__blkBridge.onDone(json);}" +
        "function start(){" +
        "if(window.requestIdleCallback)requestIdleCallback(settleLoop,{timeout:500});else settleLoop();}" +
        "window.__blkRecollect=function(){" +
        "dpr=window.devicePixelRatio||1;" +
        "var m=measure();" +
        // 超高缩放兜底(finish 未跑时在此执行;与 finish 同值幂等)
        "var ph=m.h*dpr;" +
        "if(ph>" + limitPx + "){" +
        "var z=" + limitPx + "/ph;" +
        "document.body.style.transformOrigin='0 0';" +
        "document.body.style.transform='scale('+z+')';}" +
        "collect();" +
        "m=measure();" +
        "if(chars.length>" + GEOM_MAX_CHARS + "){return '{\\\"ok\\\":0}';}" +
        "return '{\"ok\":1,\"w\":'+Math.round(m.w*dpr)+',\"h\":'+Math.round(m.h*dpr)+',\"cs\":'+JSON.stringify(chars.join(''))+',\"rs\":'+JSON.stringify(rects)+'}';};" +
        "Promise.all([loaded,fontLoads,bgLoads,fonts]).then(start);" +
        "setTimeout(function(){if(!window.__blkDone){console.log('BLK timeout ok=0');window.__blkBridge.onDone('{\"ok\":0}');}},6000);})();</script>"
}
