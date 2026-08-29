package com.yukino.tool.module.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
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
import androidx.core.content.FileProvider
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yukino.tool.TAG
import com.yukino.tool.util.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
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
                this@CustomWebView.visibility = View.VISIBLE
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
                val loadUrl = Uri.parse(url)
                val onlyOpenSameSite = isOnlyOpenSameSite()
                Log.i(TAG, "shouldOverrideUrlLoading: innerOnlyOpenSameSite: $${onlyOpenSameSite}")
                if (requestUrl.host == loadUrl.host) {
                    Log.i(TAG, "shouldOverrideUrlLoading: 系统处理(允许跳转)")
                    return openUrl(url)
                } else {
                    return if (!onlyOpenSameSite) {
                        openUrl(url)
                    } else {
                        true
                    }
                }
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