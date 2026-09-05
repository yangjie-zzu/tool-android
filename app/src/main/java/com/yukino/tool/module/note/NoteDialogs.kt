package com.yukino.tool.module.note

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 密码对话框请求: 非空时弹对话框；用户确定/取消后清空并回调结果(取消为null)，挂起方借此继续
internal class PasswordRequest(val title: String, val onResult: (String?) -> Unit)

// 设置主密码对话框请求: 回调(密码, 是否同时启用指纹)，取消为null
internal class SetupRequest(val onResult: (Pair<String, Boolean>?) -> Unit)

@Composable
internal fun PasswordDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            NoteTextField(
                value = password,
                onValueChange = { password = it },
                label = "主密码",
                modifier = Modifier.fillMaxWidth(),
                password = true
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun SetupMasterDialog(
    biometricUsable: Boolean,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var useBio by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "设置主密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "保存需要主密码，忘记将无法恢复加密内容。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NoteTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "主密码(至少4位)",
                    modifier = Modifier.fillMaxWidth(),
                    password = true
                )
                NoteTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = "确认主密码",
                    modifier = Modifier.fillMaxWidth(),
                    password = true
                )
                if (biometricUsable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "同时启用指纹(用于查看/复制)", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = useBio, onCheckedChange = { useBio = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    password.length < 4 -> Toast.makeText(context, "主密码至少4位", Toast.LENGTH_SHORT).show()
                    password != confirm -> Toast.makeText(context, "两次输入不一致", Toast.LENGTH_SHORT).show()
                    else -> onConfirm(password, useBio && biometricUsable)
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
