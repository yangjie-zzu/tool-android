package com.yukino.tool.module.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import com.yukino.tool.TAG
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Web(
    initUrl: String?,
    onlyOpenSameSite: Boolean = false,
    active: Boolean = false,
    onNew: ((url: String) -> Unit)? = null,
    onProgressChange: (progress: Float) -> Unit = {},
    onUrlChange: (url: String?) -> Unit = {},
    onTitleChange: (title: String?) -> Unit = {},
    onIconChange: (icon: Bitmap?) -> Unit = {},
    onSelected: (selectedText: String, webview: CustomWebView) -> Unit = { selectedText, webview ->
        if (selectedText.startsWith("http://") || selectedText.startsWith("https://")) {
            webview.loadUrl(selectedText)
        } else {
            Log.i(TAG, "Web: $selectedText, ${selectedText.length}")
            webview.loadUrl("https://www.google.com/search?q=${selectedText}")
        }
    },
    onHistory: ((webview: CustomWebView, url: String?, isReload: Boolean) -> Unit)? = null,
    //本webview网页历史耗尽时的返回: 多webview模式下由浏览器关闭此webview回到上一个
    onBoxBack: (() -> Unit)? = null,
    //webview创建完成回调: 引用上交给上层(浏览器)持有,关闭box时由上层负责销毁
    onWebViewReady: ((CustomWebView) -> Unit)? = null,
    //外部导航命令: navigateKey 每自增一次,就加载一次 navigateUrl(用于地址栏输入访问)
    navigateUrl: String? = null,
    navigateKey: Int = 0
) {

    val innerOnlyOpenSameSite by rememberUpdatedState(onlyOpenSameSite)

    val innerOnNew by rememberUpdatedState(onNew)

    val currentCoroutineScope = rememberCoroutineScope()

    var innerWebView: CustomWebView? by remember {
        mutableStateOf(null)
    }

    // 加载期显示优化: WebView一直可见(页面边加载边呈现)，背景色与主题一致避免白屏突兀
    val themeBackground = MaterialTheme.colorScheme.background.toArgb()

    LaunchedEffect(navigateKey) {
        if (navigateKey > 0) {
            navigateUrl?.let { innerWebView?.loadUrl(it) }
        }
    }

    // 返回优化: 当前网页有历史记录时,返回键先回退网页历史;
    // 没有历史才关闭此webview回到上一个(多webview模式)
    val context = LocalContext.current
    val backEnabled = innerWebView != null && active
    BackHandler(enabled = backEnabled) {
        val wv = innerWebView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (onBoxBack != null) {
            onBoxBack()
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    // webview销毁说明: 不能在组合期onDispose/onRelease里destroy——factory写入状态会触发
    // 组合丢弃重跑,刚创建的webview会被误destroy(渲染进程随之被杀,页面永远加载不出)。
    // 引用经onWebViewReady上交给浏览器层,关闭box时由浏览器层移除视图树后destroy
    AndroidView(
        modifier = Modifier.clipToBounds(),
        factory = {
            WebView.setWebContentsDebuggingEnabled(true)

            val webView = object : CustomWebView(it) {
                val webview = this
                override fun onPullRefresh() {
                    currentCoroutineScope.launch {
                        webview.reload()
                    }
                }

                override fun onProgressChange(progress: Float) {
                    super.onProgressChange(progress)
                    onProgressChange.invoke(progress)
                }

                override fun onIconChange(icon: Bitmap?) {
                    super.onIconChange(icon)
                    onIconChange.invoke(icon)
                }

                override fun onTitleChange(title: String?) {
                    super.onTitleChange(title)
                    onTitleChange.invoke(title)
                }

                override fun onUrlChange(url: String?) {
                    super.onUrlChange(url)
                    onUrlChange.invoke(url)
                }

                override fun isOnlyOpenSameSite(): Boolean {
                    return innerOnlyOpenSameSite
                }

                override fun openUrl(url: String?): Boolean {
                    if (url != null && !firstLoadDone) {
                        //首个页面尚未加载完成(通常是重定向中转页)，在当前webview原地跳转，避免留下空白的无标题中转box
                        Log.i(TAG, "openUrl: 首页加载中原地跳转 $url")
                        loadUrl(url)
                        return true
                    }
                    val openInNewWebView = innerOnNew
                    if (openInNewWebView != null && url != null) {
                        Log.i(TAG, "openUrl: 新webview打开 $url")
                        openInNewWebView(url)
                        return true
                    }
                    return super.openUrl(url)
                }

                override fun onSelected(selectedText: String) {
                    super.onSelected(selectedText)
                    onSelected.invoke(selectedText, webview)
                }

                override fun onHistory(webview: CustomWebView, url: String?, isReload: Boolean) {
                    super.onHistory(webview, url, isReload)
                    onHistory?.invoke(webview, url, isReload)
                }

                override fun onDownloadTriggered(url: String?) {
                    //下载链接先被当作页面新开box(其initUrl即下载url), 转入下载后本box是空白无标题页: 回收它回到上一页.
                    //不能用firstLoadDone判断: onPageFinished先于onDownloadStart触发, 首载标志已被置位
                    if (url != null && url == initUrl && title.isNullOrBlank()) {
                        Log.i(TAG, "onDownloadTriggered: 回收下载空标签 $url")
                        onBoxBack?.invoke()
                    }
                }

            }
            Log.i(TAG, "webview版本：${webView.settings.userAgentString}")
            // 背景色与主题一致: 加载期不会出现刺眼纯白
            webView.setBackgroundColor(themeBackground)
            //开启js支持，不开启js代码不会执行
            webView.settings.javaScriptEnabled = true
            webView.settings.useWideViewPort = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            //运行http和https混用
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webView.settings.mediaPlaybackRequiresUserGesture = false
            //布局参数，类似css width: 100%, height: 100%
            webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (initUrl != null) {
                webView.loadUrl(initUrl)
            }
            innerWebView = webView
            onWebViewReady?.invoke(webView)
            webView
        }
    )
}
