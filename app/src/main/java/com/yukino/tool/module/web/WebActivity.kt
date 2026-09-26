package com.yukino.tool.module.web

import WebBox
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.yukino.tool.Router
import com.yukino.tool.ui.theme.ToolTheme
import com.yukino.tool.util.IntentLauncher
import com.yukino.tool.util.PermissionRequester

lateinit var permissionRequester: PermissionRequester

lateinit var webIntentLauncher: IntentLauncher

class WebActivity: ComponentActivity() {

    companion object {
        //下载通知点击携带: 拉起浏览器并直接弹出下载页面
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }

    //下载页弹出触发版本号: 每次通知点击+1, WebBrowser观察它弹页
    private val openDownloadsTick = androidx.compose.runtime.mutableIntStateOf(0)

    private fun consumeOpenDownloads(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) {
            openDownloadsTick.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    //边到边: 状态栏区域由标题栏背景延伸覆盖(状态栏颜色经ToolTheme设为同色)
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    //导航条区域同色, 避免黑色条带
    window.navigationBarColor = android.graphics.Color.parseColor("#EEEEEE")
        permissionRequester = PermissionRequester(this)
        webIntentLauncher = IntentLauncher(this)
        // 外部"用浏览器打开"传入的链接(ACTION_VIEW的data)作为首个webview打开
        val externalUrl = if (Intent.ACTION_VIEW == intent.action) intent.dataString else null
        consumeOpenDownloads(intent)
        setContent {
            ToolTheme(
                statusBarColor = androidx.compose.ui.graphics.Color(0xFFEEEEEE),
                lightStatusBars = true
            ) {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WebBrowser(
                        initialUrl = externalUrl,
                        openDownloadsTick = openDownloadsTick.intValue
                    )
                }
            }
        }
    }

    //singleTop: 通知点击时Activity已在栈顶, 走这里
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenDownloads(intent)
    }
}