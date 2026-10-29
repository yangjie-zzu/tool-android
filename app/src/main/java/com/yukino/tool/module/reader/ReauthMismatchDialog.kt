package com.yukino.tool.module.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// 重新授权不一致详情弹窗: 逐项列出差异(仅不同项, 大小始终对比),
// 文档类型相同才可强制授权(换源到所选文件, 进度保留但内容不同可能错乱);
// 类型不同时按钮置灰并说明原因。两个阅读页(TXT/EPUB 与 PDF)共用。
@Composable
fun ReauthMismatchDialog(
    check: ReauthCheck,
    onForce: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("所选文件与原书不一致") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("所选文件与原书存在以下差异：", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                check.diffs.forEach { d ->
                    Text("· ${d.label}", style = MaterialTheme.typography.bodyMedium)
                    Text("  原：${d.old}", style = MaterialTheme.typography.bodySmall)
                    Text("  新：${d.new}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (check.forceAllowed) {
                        "强制授权将按原书进度继续阅读所选文件，内容不同可能造成章节/进度错乱。"
                    } else {
                        "文档类型不同，不能强制授权。"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(enabled = check.forceAllowed, onClick = onForce) { Text("强制授权") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
