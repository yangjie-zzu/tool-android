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
                    WebBrowser(initialUrl = externalUrl)
                }
            }
        }
    }
}