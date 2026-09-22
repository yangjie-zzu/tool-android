package com.yukino.tool.db

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState

/*
 * 强制迁移页: 升级后检测到旧数据时全屏展示, 不可返回跳过。
 * 实时显示四模块的迁移进度/条数与滚动日志; note 为旧加密格式时内联收集主密码。
 */
@Composable
fun MigrationScreen(
    onFinished: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    BackHandler(enabled = true) { /* 迁移期间禁止返回 */ }
    val state by DataMigrator.state.collectAsState()
    var showPassword by remember { mutableStateOf(false) }

    // note 迁移等待密码时弹输入框
    LaunchedEffect(state.needPassword) {
        if (state.needPassword) showPassword = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Text("数据迁移", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "检测到旧版本数据，正在迁移到新数据库…",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))

        state.modules.forEach { m ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row {
                    val (symbol, color) = when (m.phase) {
                        DataMigrator.Phase.DONE -> "✔" to Color(0xFF2E7D32)
                        DataMigrator.Phase.RUNNING -> "▶" to MaterialTheme.colorScheme.primary
                        DataMigrator.Phase.FAILED -> "✘" to MaterialTheme.colorScheme.error
                        DataMigrator.Phase.SKIPPED -> "✔" to MaterialTheme.colorScheme.onSurfaceVariant
                        DataMigrator.Phase.PENDING -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(symbol, color = color)
                    Spacer(Modifier.width(8.dp))
                    Text(m.module.label)
                }
                val status = when (m.phase) {
                    DataMigrator.Phase.DONE -> if (m.total > 0) "完成 · ${m.total}条" else "完成"
                    DataMigrator.Phase.SKIPPED -> "无旧数据"
                    DataMigrator.Phase.RUNNING ->
                        if (m.total > 0) "${m.message.ifBlank { "迁移中" }} ${m.processed}/${m.total}"
                        else "读取中…"
                    DataMigrator.Phase.FAILED -> m.message.ifBlank { "失败" }
                    DataMigrator.Phase.PENDING -> "等待"
                }
                Text(
                    status,
                    fontSize = 13.sp,
                    color = if (m.phase == DataMigrator.Phase.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("日志", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        val listState = rememberLazyListState()
        LaunchedEffect(state.logs.size) {
            if (state.logs.isNotEmpty()) listState.animateScrollToItem(state.logs.size - 1)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(state.logs) { line ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))
        when {
            state.finished && state.allSuccess -> {
                Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("进入应用") }
            }
            state.finished -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("跳过失败项") }
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("重试失败项") }
                }
            }
        }
    }

    // 主密码输入(note v1/v2旧格式迁移)
    if (showPassword && state.needPassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { /* 必须输入或明确跳过 */ },
            title = { Text("备忘录主密码") },
            text = {
                Column {
                    Text("备忘录为旧加密格式，需要验证主密码才能迁移数据。")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("主密码") },
                        isError = state.passwordError != null,
                        supportingText = { state.passwordError?.let { Text(it) } },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = password.isNotBlank(),
                    onClick = {
                        showPassword = false
                        DataMigrator.submitPassword(password)
                    }
                ) { Text("验证并迁移") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showPassword = false
                    DataMigrator.submitPassword(null)
                }) { Text("跳过备忘录") }
            }
        )
    }
}
