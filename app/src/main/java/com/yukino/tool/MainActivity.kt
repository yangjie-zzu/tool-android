package com.yukino.tool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yukino.tool.db.DataMigrator
import com.yukino.tool.db.MigrationScreen
import com.yukino.tool.ui.theme.ToolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val TAG = "Tool"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {

            ToolTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppEntry()
                }
            }
        }
    }
}

/*
 * 启动门卫: 升级后存在旧数据且迁移未完成 → 强制迁移页(不可返回);
 * 全新安装/已迁完 → 正常进应用(全新安装顺带静默补迁移标记)。
 */
@Composable
private fun AppEntry() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<Int?>(null) }   // null=检测中, 0=迁移, 1=正常

    LaunchedEffect(Unit) {
        val need = withContext(Dispatchers.IO) { DataMigrator.needsMigration(context) }
        if (need) {
            DataMigrator.start(context)
            mode = 0
        } else {
            withContext(Dispatchers.IO) { DataMigrator.markFreshInstall(context) }
            mode = 1
        }
    }

    when (mode) {
        null -> Box(Modifier.fillMaxSize())   // 迁移标记检测是毫秒级, 空屏一闪而过
        0 -> MigrationScreen(
            onFinished = { mode = 1 },
            onRetry = { DataMigrator.retryFailed(context) },
            onSkip = { DataMigrator.skipFailed(context) }
        )
        else -> Router()
    }
}
