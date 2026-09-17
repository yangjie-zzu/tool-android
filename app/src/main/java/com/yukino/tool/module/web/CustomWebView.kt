package com.yukino.tool.module.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yukino.tool.TAG
import com.yukino.tool.util.findActivity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.core.isNotEmpty
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

interface WebInterface {
    fun onPullRefresh() {}
    fun onCreateSelectorActionMode(mode: ActionMode?, menu: Menu?, callback: ActionMode.Callback?) {}
    fun onSelected(selectedText: String) {}
    fun onProgressChange(progress: Float) {}
    fun onIconChange(icon: Bitmap?) {}
    fun onTitleChange(title: String?) {}
    fun onUrlChange(url: String?) {}
    fun onHistory(webview: CustomWebView, url: String?, isReload: Boolean) {}
    fun isOnlyOpenSameSite(): Boolean {
        return false
    }
    fun openUrl(url: String?): Boolean {
        return false
    }
}

open class CustomWebView(context: Context) : WebView(context), WebInterface {

    // 首个页面(含重定向链)是否已完成加载: 完成前的跳转在当前webview原地加载，避免新开空白box
    var firstLoadDone = false

    private var isTop = true

    private var isRefreshing = false

    private var downY: Float? = null

    private var isPull: Boolean? = null

    init {
        this.webChromeClient = object : WebChromeClient() {

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
                findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    if (fileChooserParams != null) {
                        if (fileChooserParams.isCaptureEnabled) {
                            val isImage = fileChooserParams.acceptTypes.find { it.contains("image/") } != null
                            val isVideo = fileChooserParams.acceptTypes.find { it.contains("video/") } != null
                            val dir = File(context.filesDir, "web")
                            if (!dir.exists()) {
                                dir.mkdirs()
                            }
                            val file = File(dir, "WEB_${System.currentTimeMillis()}.${if (isImage) "jpg" else if (isVideo) "mp4" else "temp"}")
                            file.createNewFile()
                            val uri = FileProvider.getUriForFile(context, context.applicationContext.packageName + ".fileProvider", file)
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

        this.webViewClient = object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                urlParam: String?,
                favicon: Bitmap?
            ) {
                onUrlChange(urlParam ?: "")
                super.onPageStarted(view, urlParam, favicon)
                Log.i(TAG, "onPageStarted: ${view?.url}")
            }

            override fun doUpdateVisitedHistory(
                view: WebView?,
                url: String?,
                isReload: Boolean
            ) {
                Log.i(TAG, "doUpdateVisitedHistory: ${url}, $isReload")
                onHistory(this@CustomWebView, url, isReload)
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            //加载完成处理
            override fun onPageFinished(view: WebView?, url: String?) {
                firstLoadDone = true
                super.onPageFinished(view, url)
                Log.i(TAG, "onPageFinished: ${view?.url}")
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
                        context.startActivity(Intent.parseUri(requestUrl?.toString(), Intent.URI_INTENT_SCHEME))
                        return true
                    } catch (e: ActivityNotFoundException) {
                        Log.e(TAG, "shouldOverrideUrlLoading: ", e)
                        return true
                    }
                }
                val targetHost = requestUrl.host
                val currentHost = Uri.parse(url).host
                val sameSite = isSameSite(targetHost, currentHost)
                val onlyOpenSameSite = isOnlyOpenSameSite()
                Log.i(TAG, "shouldOverrideUrlLoading: target=$targetHost current=$currentHost sameSite=$sameSite onlyOpenSameSite=$onlyOpenSameSite")
                if (sameSite || !onlyOpenSameSite) {
                    return openUrl(requestUrl.toString())
                }
                Log.i(TAG, "shouldOverrideUrlLoading: 跨站已拦截")
                return true
            }
        }

        //下载处理
        this.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "onDownloadStart: $url, $userAgent, $contentDisposition, $mimetype, $contentLength")
            if (url.isNotEmpty()) {
                downloadFile(url)
            }
        }
    }

    //下载文件到公共下载目录(Downloads/tool)，通知栏展示进度
    private fun downloadFile(url: String) {
        val name = url.split('?').first().split('/').last()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel("download", "download", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        CoroutineScope(Dispatchers.IO).launch {
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
                            val now = System.currentTimeMillis()
                            if (now - startTime > 1000 || finish >= len) {
                                builder.setContentTitle("下载中: $name").setProgress(len.toInt(), finish.toInt(), false)
                                notificationManager.notify(1, builder.build())
                            }
                            if (finish >= len) {
                                Log.i(TAG, "downloadFile: 下载完成 $name")
                                builder.setContentTitle("下载完成: $name").setSmallIcon(android.R.drawable.stat_sys_download_done)
                                    .setAutoCancel(true)
                                notificationManager.notify(1, builder.build())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                builder.setContentTitle("下载失败: $name").setContentText(e.message).setSmallIcon(android.R.drawable.stat_notify_error).setAutoCancel(true)
                notificationManager.notify(1, builder.build())
                Log.e(TAG, "downloadFile: ", e)
            }
        }
    }

    override fun onOverScrolled(sX: Int, sY: Int, clampedX: Boolean, clampedY: Boolean) {
        isTop = sY == 0 && clampedY
        return super.onOverScrolled(sX, sY, clampedX, clampedY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            downY = event.y
        }
        if (event?.action == MotionEvent.ACTION_MOVE) {
            if (isPull == null) {
                val y = event.y
                downY?.let {
                    if (abs(y - it) > ViewConfiguration.get(context).scaledTouchSlop) {
                        isPull = y > it
                    }
                }
            }
        }
        if (event?.action == MotionEvent.ACTION_UP) {
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val tempDownY = downY
                    val upY = event.y
                    if (isPull == true && tempDownY != null && tempDownY < (this@CustomWebView.bottom - this@CustomWebView.top) * 0.7 && upY - tempDownY > 500) {
                        if (isTop && !isRefreshing) {
                            isRefreshing = true
                            onPullRefresh()
                        }
                    }
                } finally {
                    this@CustomWebView.resetPullRefreshState()
                }
            }

        }
        return super.onTouchEvent(event)
    }

    private fun resetPullRefreshState() {
        isTop = true
        isRefreshing = false
        downY = null
        isPull = null
    }

    //常见二级公共后缀(co.uk/com.cn等)，用于注册域判断
    private val secondLevelSuffixes = setOf("co", "com", "net", "org", "gov", "edu", "ac")

    //注册域: www.google.com -> google.com, www.google.co.uk -> google.co.uk
    private fun registrableDomain(host: String): String {
        val parts = host.split(".").filter { it.isNotEmpty() }
        if (parts.size < 2) return host
        if (parts.size >= 3 && secondLevelSuffixes.contains(parts[parts.size - 2])) {
            return parts.takeLast(3).joinToString(".")
        }
        return parts.takeLast(2).joinToString(".")
    }

    //同站判断: 注册域相同即视为同站(子域名互通)
    private fun isSameSite(hostA: String?, hostB: String?): Boolean {
        if (hostA == hostB) return true
        if (hostA == null || hostB == null) return false
        return registrableDomain(hostA) == registrableDomain(hostB)
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode {
        Log.i(TAG, "startActionMode: 2, $type")
        val actionMode = super.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                onCreateSelectorActionMode(mode, menu, callback)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                return callback!!.onPrepareActionMode(mode, menu)
            }

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                return callback!!.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                return callback!!.onDestroyActionMode(mode)
            }

            override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect?) {
                (callback as ActionMode.Callback2).onGetContentRect(mode, view, outRect)
            }

        }, type)
        return actionMode
    }

    private fun getSelectedText(): CompletableFuture<String> {
        val promise = CompletableFuture<String>()
        this.evaluateJavascript("window.getSelection().toString()") { result ->
            promise.complete(if (result.isNotEmpty()) result.substring(1, result.length - 1) else result)
        }
        return promise
    }

    override fun onCreateSelectorActionMode(
        mode: ActionMode?,
        menu: Menu?,
        callback: ActionMode.Callback?
    ) {
        super.onCreateSelectorActionMode(mode, menu, callback)
        val menuItem = menu?.add("访问")
        menuItem?.setOnMenuItemClickListener {
            getSelectedText().thenAccept {
                onSelected(it)
                mode?.finish()
            }
            true
        }
    }

}