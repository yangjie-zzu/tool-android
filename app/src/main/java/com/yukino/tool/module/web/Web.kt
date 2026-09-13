package com.yukino.tool.module.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    //外部导航命令: navigateKey 每自增一次,就加载一次 navigateUrl(用于地址栏输入访问)
    navigateUrl: String? = null,
    navigateKey: Int = 0
) {

    val innerOnlyOpenSameSite by rememberUpdatedState(onlyOpenSameSite)

    val currentCoroutineScope = rememberCoroutineScope()

    var innerWebView: WebView? by remember {
        mutableStateOf(null)
    }

    LaunchedEffect(navigateKey) {
        if (navigateKey > 0) {
            navigateUrl?.let { innerWebView?.loadUrl(it) }
        }
    }

    // 返回优化: 当前网页有历史记录时,返回键先回退网页历史;
    // 没有历史(首页)才交还给系统返回(关闭页面)
    val context = LocalContext.current
    val backEnabled = innerWebView != null && active
    BackHandler(enabled = backEnabled) {
        val wv = innerWebView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    DisposableEffect(innerWebView, active) {
        if (active) {
            innerWebView?.onResume()
        } else {
            innerWebView?.onPause()
        }
        onDispose {
            innerWebView?.onPause()
        }
    }

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
                    if (url != null && visibility != View.VISIBLE) {
                        //首个页面尚未加载完成(通常是重定向中转页)，在当前webview原地跳转，避免留下空白的无标题中转box
                        Log.i(TAG, "openUrl: 首页加载中原地跳转 $url")
                        loadUrl(url)
                        return true
                    }
                    //跳转一律在当前webview加载，不再新开
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

            }
            Log.i(TAG, "webview版本：${webView.settings.userAgentString}")
            webView.visibility = View.INVISIBLE
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
            webView
        }
    )
}
