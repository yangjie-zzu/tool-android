package com.yukino.tool.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import com.yukino.tool.TAG
import com.yukino.tool.util.findActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowser(
    onWebView: (WebView) -> Unit = {}
) {

    var url by rememberSaveable {
        mutableStateOf("https://www.google.com")
    }

    var enableLoadNewUrl by remember {
        mutableStateOf(true)
    }

    //加载进度
    var progress by remember {
        mutableFloatStateOf(0f)
    }

    //内部webview
    var innerWebView: WebView? by remember {
        mutableStateOf(null)
    }

    val urlState = rememberTextFieldState(initialText = url)

    val rememberCoroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Gray)
                .height(40.dp)
                .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
                ,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BasicTextField(
                modifier = Modifier.weight(1f),
                state = urlState,
                lineLimits = TextFieldLineLimits.SingleLine,
                decorator = { innerTextField ->
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(40.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 15.dp, vertical = 2.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            innerTextField()
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = {
                    url = urlState.text.toString()
                    innerWebView?.loadUrl(url)
                },
            )
            Switch(checked = enableLoadNewUrl, onCheckedChange = {
                enableLoadNewUrl = it
            })
        }
        //如果未加载完成，则显示进度条
        if (progress < 1) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }
        Box(
            modifier = Modifier
//                .height(200.dp)
                .weight(1f)
        ) {
            AndroidView(
                factory = {
                    WebView.setWebContentsDebuggingEnabled(true)
                    MyWebView(it).apply {
                        val webView = this
                        Log.i(TAG, "webview版本：${webView.settings.userAgentString}")
                        webView.visibility = View.INVISIBLE
                        //开启js支持，不开启js代码不会执行
                        webView.settings.javaScriptEnabled = true
                        webView.settings.useWideViewPort = true
                        webView.settings.domStorageEnabled = true
                        //运行http和https混用
                        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        webView.settings.mediaPlaybackRequiresUserGesture = false
                        //布局参数，类似css width: 100%, height: 100%
                        webView.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        //浏览器设置
                        webView.webChromeClient = object : WebChromeClient() {


                            //加载进度条处理
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = (newProgress * 1.0 / 100).toFloat()
                                super.onProgressChanged(view, newProgress)
                            }

                            //js日志处理
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                consoleMessage?.apply {
                                    if (messageLevel() == ConsoleMessage.MessageLevel.LOG) {
                                        Log.i(
                                            TAG,
                                            "console: ${message()}\tat ${sourceId()} line ${lineNumber()}"
                                        )
                                    }
                                    if (messageLevel() == ConsoleMessage.MessageLevel.DEBUG) {
                                        Log.d(
                                            TAG,
                                            "console: ${message()}\tat ${sourceId()} line ${lineNumber()}"
                                        )
                                    }
                                    if (messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                                        Log.w(
                                            TAG,
                                            "console: ${message()}\tat ${sourceId()} line ${lineNumber()}"
                                        )
                                    }
                                    if (messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        Log.e(
                                            TAG,
                                            "console: ${message()}\tat ${sourceId()} line ${lineNumber()}"
                                        )
                                    }
                                    if (messageLevel() == ConsoleMessage.MessageLevel.TIP) {
                                        Log.i(
                                            TAG,
                                            "console: ${message()}\tat ${sourceId()} line ${lineNumber()}"
                                        )
                                    }
                                }
                                return true
                            }

                            //选择文件处理
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                Log.d(
                                    TAG,
                                    "onShowFileChooser: $fileChooserParams ${fileChooserParams?.isCaptureEnabled}"
                                )
                                return false
                            }

                            //权限请求处理
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                val requestedResources = request?.resources
                                if (requestedResources != null) {
                                    for (r in requestedResources) {
                                        // In this sample, we only accept video capture request.
                                        if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                                            if (context.findActivity()
                                                    .checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                                            ) {
                                                ActivityCompat.requestPermissions(
                                                    context.findActivity(), arrayOf(
                                                        Manifest.permission.CAMERA
                                                    ), 100
                                                )
                                            } else {
                                                request.grant(arrayOf(r))
                                            }
                                            return
                                        }
                                    }
                                }
                                super.onPermissionRequest(request)
                            }
                        }
                        //当前页面客户端设置
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                urlParam: String?,
                                favicon: Bitmap?
                            ) {
                                urlState.edit {
                                    this.replace(0, this.length, urlParam ?: "")
                                }
                                Log.i(TAG, "onPageStarted: ")
                                super.onPageStarted(view, urlParam, favicon)
                            }
                            //加载完成处理
                            override fun onPageFinished(view: WebView?, url: String?) {
                                webView.visibility = View.VISIBLE
                                super.onPageFinished(view, url)
                            }

                            //拦截h5资源请求
                            @SuppressLint("RestrictedApi")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                return super.shouldInterceptRequest(view, request)
                            }

                            //忽略ssl错误
                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
//                            handler?.proceed()
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val requestUrl = request?.url
                                val loadUrl = Uri.parse(url)
                                Log.i(
                                    TAG,
                                    "shouldOverrideUrlLoading: ${requestUrl?.host == loadUrl.host}, ${requestUrl?.host}, ${loadUrl.host}"
                                )
                                if (requestUrl?.host == loadUrl.host) {
                                    Log.i(TAG, "shouldOverrideUrlLoading: 系统处理")
                                    return false
                                } else {
                                    Log.i(TAG, "shouldOverrideUrlLoading: 不处理")
                                    return !enableLoadNewUrl
                                }
                            }
                        }
                        webView.scrollListener = object : ScrollListener {
                            override suspend fun onPullRefresh() {
                                withContext(rememberCoroutineScope.coroutineContext) {
                                    webView.reload()
                                }

                            }
                        }
                        webView.loadUrl(url)
                        innerWebView = webView
                        onWebView(webView)
                    }
                }
            )

        }
    }

}