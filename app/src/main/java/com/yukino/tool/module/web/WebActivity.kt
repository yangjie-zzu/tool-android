package com.yukino.tool.module.web

import WebBox
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
        permissionRequester = PermissionRequester(this)
        webIntentLauncher = IntentLauncher(this)
        setContent {
            ToolTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WebBrowser()
                }
            }
        }
    }
}