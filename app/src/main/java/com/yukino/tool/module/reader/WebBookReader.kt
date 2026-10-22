package com.yukino.tool.module.reader

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yukino.tool.module.reader.common.ReaderBook
import java.io.File

// WEB 格式书籍阅读页: WebView 直接渲染源文件(html 系/图片/导入文本)。
// 源内容登记时已转存本地(cachePath), 只记最后阅读时间, 不做阅读进度。
@Composable
fun WebBookReader(
    book: ReaderBook,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // 返回键退出阅读态回书架(不注册会走默认行为直接退出整个 Activity)
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回书架")
            }
            Text(
                book.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider()
        val file = remember(book.cachePath) { File(book.cachePath) }
        if (file.exists()) {
            val webView = remember { createWebView(context) }
            DisposableEffect(Unit) { onDispose { webView.destroy() } }
            AndroidView(
                factory = { webView },
                update = { view ->
                    if (view.url == null) view.loadUrl(android.net.Uri.fromFile(file).toString())
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                "文件不存在: ${book.cachePath}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(context: android.content.Context): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )
    settings.javaScriptEnabled = true
    settings.allowFileAccess = true
    // file:// 无响应头声明编码, 统一按 UTF-8 解码(导入文本落盘即 UTF-8)
    settings.defaultTextEncodingName = "UTF-8"
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    webViewClient = WebViewClient()
}
