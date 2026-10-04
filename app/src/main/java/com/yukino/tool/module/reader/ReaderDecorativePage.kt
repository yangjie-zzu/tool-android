package com.yukino.tool.module.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.File

// 装饰章(扉页/封面等 CSS 排版页)的 WebView 呈现:
// 加载 EPUB 解压目录的原始 xhtml(css/字体相对引用经拦截器映射到解压目录),
// 禁用 JS(纯 CSS 渲染),夜间模式注入反色滤镜,渲染进程崩溃置 failed 由调用方降级。
private const val DECOR_HOST = "book.local"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DecorativeChapterView(
    docUrl: String,
    rootDir: File,
    night: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = false          // 装饰页无脚本,纯 CSS 渲染
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.textZoom = 100                     // 装饰页固定原书设计字号
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient = object : WebViewClient() {
                // 拦截本机虚拟域映射到解压目录;外部请求一律空响应(离线且防书内外链)
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url
                    if (url.host != DECOR_HOST) return emptyResponse()
                    val rel = url.path?.trimStart('/') ?: return emptyResponse()
                    val f = File(rootDir, rel)
                    if (!f.canonicalFile.path.startsWith(rootDir.canonicalFile.path) || !f.isFile) {
                        return emptyResponse()
                    }
                    return try {
                        WebResourceResponse(mimeOf(f), mimeCharset(f), f.inputStream())
                    } catch (e: Exception) {
                        emptyResponse()
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    ready = true
                }

                // 渲染进程被系统回收: 标记失败,调用方降级
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    failed = true
                    return true
                }
            }
        }
    }

    // 夜间模式: 反色+色相旋转(白底变暗,彩色近似保留);就绪后与夜间状态变化时注入
    LaunchedEffect(webView, ready, night) {
        if (ready) applyNight(webView, night)
    }
    LaunchedEffect(webView, docUrl) {
        ready = false
        webView.loadUrl(docUrl)
    }

    if (failed) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "页面渲染失败，请返回重试",
                color = androidx.compose.ui.graphics.Color(0xFF888888)
            )
        }
        return
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
        update = { v ->
            if (ready) applyNight(v, night)
        }
    )
    if (!ready) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

private fun applyNight(view: WebView, night: Boolean) {
    view.evaluateJavascript(
        "document.documentElement.style.filter = " +
            (if (night) "'invert(0.92) hue-rotate(180deg)'" else "'none'"), null
    )
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
