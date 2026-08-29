package com.yukino.tool.module.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import com.yukino.tool.TAG
import com.yukino.tool.util.rememberCurrentActivity
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
    enableBack: Boolean,
    onHistory: ((webview: CustomWebView, url: String?, isReload: Boolean) -> Unit)? = null
) {

    val innerOnlyOpenSameSite by rememberUpdatedState(onlyOpenSameSite)

    val currentCoroutineScope = rememberCoroutineScope()

    val activity = rememberCurrentActivity() as ComponentActivity

    var innerWebView: WebView? by remember {
        mutableStateOf(null)
    }

    val onBackPressedCallback = remember {
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.i(TAG, "web handleOnBackPressed: ${innerWebView?.canGoBack()}")
                innerWebView?.evaluateJavascript("window.history.back();", null)
                innerWebView?.let {
                    for (i in 0 until it.copyBackForwardList().size) {
                        Log.i(TAG, "copyBackForwardList${i}: ${it.copyBackForwardList().getItemAtIndex(i).url}")
                    }
                }
            }
        }
    }

    DisposableEffect(innerWebView, enableBack) {
        if (enableBack) {
            activity.onBackPressedDispatcher.addCallback(onBackPressedCallback)
        }
        onDispose {
            onBackPressedCallback.remove()
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

                override fun onSelected(selectedText: String) {
                    super.onSelected(selectedText)
                    onSelected.invoke(selectedText, webview)
                }

                override fun onHistory(webview: CustomWebView, url: String?, isReload: Boolean) {
                    super.onHistory(webview, url, isReload)
                    onHistory?.invoke(webview, url, isReload)
                    onBackPressedCallback.isEnabled = webview.canGoBack()
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
