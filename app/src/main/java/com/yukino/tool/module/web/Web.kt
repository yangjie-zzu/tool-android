package com.yukino.tool.module.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.yukino.tool.TAG
import com.yukino.tool.util.findActivity
import com.yukino.tool.util.rememberCurrentActivity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.core.isNotEmpty
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Web(
    url: String?,
    openOffSite: Boolean = true,
    onProgressChange: (progress: Float) -> Unit = {},
    onUrlChange: (url: String) -> Unit = {},
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
    webIndex: Int,
    enableBack: Boolean,
    onHistory: ((webview: CustomWebView, url: String?, isReload: Boolean) -> Unit)? = null
) {

    val currentCoroutineScope = rememberCoroutineScope()

    val activity = rememberCurrentActivity() as ComponentActivity

    var innerWebView: WebView? by remember {
        mutableStateOf(null)
    }

    LaunchedEffect(url) {
        if (url != null) {
            innerWebView?.loadUrl(url)
        }
    }

    val onBackPressedCallback = remember {
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.i(TAG, "web handleOnBackPressed: ${innerWebView?.canGoBack()}")
                innerWebView?.goBack()
                innerWebView?.let {
                    for (i in 0 until it.copyBackForwardList().size) {
                        Log.i(TAG, "copyBackForwardList${i}: ${it.copyBackForwardList().getItemAtIndex(i).url}")
                    }
                }
                this.isEnabled = innerWebView?.canGoBack() ?: false
                Log.i(TAG, "web(${webIndex})回退: ${this.isEnabled}")
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

    AndroidView(
        modifier = Modifier.clipToBounds(),
        factory = { it ->
            WebView.setWebContentsDebuggingEnabled(true)
            CustomWebView(it).apply {
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
                        val progress = (newProgress * 1.0 / 100).toFloat()
                        onProgressChange(progress)
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
                        Log.d(TAG, "onShowFileChooser: $fileChooserParams, ${fileChooserParams?.isCaptureEnabled}, ${fileChooserParams?.acceptTypes}")
                        currentCoroutineScope.launch {
                            if (fileChooserParams != null) {
                                if (fileChooserParams.isCaptureEnabled) {
                                    val isImage = fileChooserParams.acceptTypes.find { it.contains("image/") } != null
                                    val isVideo = fileChooserParams.acceptTypes.find { it.contains("video/") } != null
                                    val dir = File(activity.filesDir, "web")
                                    if (!dir.exists()) {
                                        dir.mkdirs()
                                    }
                                    val file = File(dir, "WEB_${System.currentTimeMillis()}.${if (isImage) "jpg" else if (isVideo) "mp4" else "temp"}")
                                    file.createNewFile()
                                    val uri = FileProvider.getUriForFile(activity, activity.applicationContext.packageName + ".fileProvider", file)
                                    val getCameraIntent = {
                                        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also {
                                            it.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                                            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                        }
                                    }
                                    val getVideoIntent = {
                                        Intent(MediaStore.ACTION_VIDEO_CAPTURE).also {
                                            it.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                                            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                        }
                                    }
                                    val intent = if (isImage && isVideo) {
                                        Intent(Intent.ACTION_CHOOSER).also {
                                            it.putExtra(Intent.EXTRA_TITLE, "摄像模式")
                                            it.putExtra(Intent.EXTRA_INTENT, arrayOf(getCameraIntent(), getVideoIntent()))
                                        }
                                    } else if (isVideo) {
                                        getVideoIntent()
                                    } else {
                                        getCameraIntent()
                                    }
                                    Log.i(TAG, "send: $intent")
                                    val result = webIntentLauncher.send(intent)
                                    Log.i(TAG, "camera result: $result")
                                    if (result?.resultCode == Activity.RESULT_OK) {
                                        filePathCallback?.onReceiveValue(arrayOf(uri))
                                    } else {
                                        filePathCallback?.onReceiveValue(null)
                                    }
                                } else {
                                    val result = webIntentLauncher.send(fileChooserParams.createIntent())
                                    if (result != null) {
                                        filePathCallback?.onReceiveValue(FileChooserParams.parseResult(result.resultCode, result.data))
                                    }
                                }
                            }
                        }
                        return true
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

                    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                        onIconChange(icon)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        onTitleChange(title)
                    }
                }
                //当前页面客户端设置
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        urlParam: String?,
                        favicon: Bitmap?
                    ) {
                        onUrlChange(urlParam ?: "")
                        super.onPageStarted(view, urlParam, favicon)
                    }

//                    override fun doUpdateVisitedHistory(
//                        view: WebView?,
//                        url: String?,
//                        isReload: Boolean
//                    ) {
//                        Log.i(TAG, "doUpdateVisitedHistory: ${url}, ${isReload}")
//                        onBackPressedCallback.isEnabled = webView.canGoBack()
//                        onHistory?.invoke(webView, url, isReload)
//                        super.doUpdateVisitedHistory(view, url, isReload)
//                    }

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
                        val scheme = requestUrl?.scheme
                        if (scheme != "http" && scheme != "https") {
                            try {
                                activity.startActivity(Intent.parseUri(requestUrl?.toString(), Intent.URI_INTENT_SCHEME))
                                return true
                            } catch (e: ActivityNotFoundException) {
                                Log.e(TAG, "shouldOverrideUrlLoading: ", e)
                                return true
                            }
                        }
                        val loadUrl = Uri.parse(url)
//                                Log.i(TAG, "shouldOverrideUrlLoading: ${requestUrl?.host == loadUrl.host}, ${requestUrl?.host}, ${loadUrl.host}")
                        if (requestUrl.host == loadUrl.host) {
                            Log.i(TAG, "shouldOverrideUrlLoading: 系统处理(允许跳转)")
                            return false
                        } else {
                            Log.i(TAG, "shouldOverrideUrlLoading: 允许跳转: $openOffSite")
                            return !openOffSite
                        }
                    }
                }
                webView.scrollListener = object : ScrollListener {
                    override suspend fun onPullRefresh() {
                        withContext(currentCoroutineScope.coroutineContext) {
                            webView.reload()
                        }
                    }
                }
                webView.setDownloadListener(object : DownloadListener {
                    override fun onDownloadStart(
                        url: String?,
                        userAgent: String?,
                        contentDisposition: String?,
                        mimetype: String?,
                        contentLength: Long
                    ) {
                        Log.i(TAG, "onDownloadStart: $url, $userAgent, $contentDisposition, $mimetype, $contentLength")
                        if (url == null) {
                            return
                        }
                        val name = url.split('?').first().split('/').last()
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            notificationManager.createNotificationChannel(
                                NotificationChannel("download", "download", NotificationManager.IMPORTANCE_DEFAULT)
                            )
                        }
                        currentCoroutineScope.launch {
                            val builder = NotificationCompat.Builder(context, "download")
                                .setContentTitle("准备下载").setContentText(name).setSmallIcon(android.R.drawable.stat_sys_download)
                            try {
                                if (permissionRequester.request(Manifest.permission.POST_NOTIFICATIONS)) {
                                    notificationManager.notify(1, builder.build())
                                }
                                val httpClient = HttpClient {
                                    install(HttpTimeout) {
                                        requestTimeoutMillis = 10000
                                    }
                                }
                                httpClient.prepareGet(url) {
                                    timeout {
                                        connectTimeoutMillis = 300000
                                        requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                                    }
                                }.execute { res ->
                                    val len = res.headers[HttpHeaders.ContentLength]?.toLong() ?: 0L
                                    val startTime = System.currentTimeMillis()
                                    var finish = 0L
                                    val downloadPath = Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOWNLOADS).absolutePath
                                    val dir = File("$downloadPath/tool")
                                    if (!dir.exists()) {
                                        dir.mkdirs()
                                    }
                                    val file = File(dir, name)
                                    file.createNewFile()
                                    val randomAccessFile = RandomAccessFile(file, "rw")
                                    randomAccessFile.setLength(len)
                                    val channel = res.bodyAsChannel()
                                    while (!channel.isClosedForRead) {
                                        val packet = channel.readRemaining(limit = DEFAULT_BUFFER_SIZE.toLong())
                                        while (packet.isNotEmpty) {
                                            val bytes = packet.readBytes()
                                            randomAccessFile.write(bytes)
                                            finish += bytes.size
                                            Log.i(TAG, "progress: $finish")
                                            val now = System.currentTimeMillis()
                                            if (now - startTime > 1000 || finish >= len) {
                                                builder.setContentTitle("下载中: $name").setProgress(len.toInt(), finish.toInt(), false)
                                                notificationManager.notify(1, builder.build())
                                            }
                                            if (finish >= len) {
                                                Log.i(TAG, "onDownloadStart: 下载完成")
                                                builder.setContentTitle("下载完成: $name").setSmallIcon(android.R.drawable.stat_sys_download_done)
                                                    .setAutoCancel(true).setContentIntent(
                                                        PendingIntent.getActivity(activity, 0, Intent(
                                                            Intent.ACTION_VIEW), PendingIntent.FLAG_IMMUTABLE)
                                                    )
                                                notificationManager.notify(1, builder.build())
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                builder.setContentTitle("下载失败: $name").setContentText(e.message).setSmallIcon(android.R.drawable.stat_notify_error).setAutoCancel(true)
                                notificationManager.notify(1, builder.build())
                                Log.e(TAG, "onDownloadStart: ", e)
                            }
                        }
                    }

                })
                if (url != null) {
                    webView.loadUrl(url)
                }
                webView.onCreateSelectorActionMode = { mode, menu, callback ->
                    val menuItem = menu?.add("访问")
                    menuItem?.setOnMenuItemClickListener {
                        val self = this;
                        currentCoroutineScope.launch {
                            val selectedText = self.getSelectedText()
                            onSelected(selectedText, self)
                            mode?.finish()
                        }
                        true
                    }
                }
                innerWebView = webView
            }
        }
    )
}