package com.yukino.tool.module.note

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 设置页: 主密码/指纹的按需管理 + 导出备份
@Composable
internal fun SettingsPage(
    entries: List<NoteEntry>,
    onUnlock: suspend (String) -> Map<String, String>?,
    onSetupMaster: () -> Unit,
    onChangeMasterPassword: suspend (String, String) -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit
) {
    val context = LocalContext.current
    val masterReady = NoteCrypto.masterReady(context)
    val bioEnabled = NoteCrypto.biometricEnabled(context)
    val scope = rememberCoroutineScope()
    var showExport by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        VaultTopBar("设置")
        Column(
            modifier = Modifier
                .padding(15.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "主密码: ${if (masterReady) "已设置" else "未设置(首次保存时设置)"}",
                    modifier = Modifier.weight(1f)
                )
                if (!masterReady) {
                    TextButton(onClick = onSetupMaster) { Text(text = "设置") }
                } else {
                    TextButton(onClick = { showChangePassword = true }) { Text(text = "修改") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "指纹: ${if (bioEnabled) "已启用(查看/复制时验证)" else if (masterReady) "未启用" else "需先设置主密码"}",
                    modifier = Modifier.weight(1f)
                )
                if (masterReady && !bioEnabled) {
                    TextButton(onClick = onEnableBiometric) { Text(text = "启用") }
                }
                if (bioEnabled) {
                    TextButton(onClick = onDisableBiometric) { Text(text = "停用") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "导出备份")
                    Text(
                        text = "加密ZIP压缩包，可分享到电脑留存",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showExport = true }) { Text(text = "导出") }
            }
        }
    }
    //导出对话框: 设置压缩包名称/密码/是否明文
    if (showExport) {
        ExportDialog(
            entries = entries,
            onUnlock = onUnlock,
            onDismiss = { showExport = false },
            onExport = { name, password, plaintext ->
                scope.launch {
                    try {
            val zip = if (plaintext) {
                //明文导出包含全部密码和2FA密钥，必须先通过认证
                val secrets = onUnlock("导出明文") ?: return@launch
                val text = withContext(Dispatchers.IO) {
                    NoteExport.buildPlaintextText(entries, secrets)
                }
                withContext(Dispatchers.IO) {
                    NoteExport.exportPlaintextZip(context, name, password, text)
                }
            } else {
                            withContext(Dispatchers.IO) {
                                NoteExport.exportEncryptedBackupZip(context, name, password)
                            }
                        }
                        showExport = false
                        NoteExport.share(context, zip)
                    } catch (e: Exception) {
                        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
    //修改主密码对话框
    if (showChangePassword) {
        ChangePasswordDialog(
            onConfirm = { oldPw, newPw ->
                scope.launch { onChangeMasterPassword(oldPw, newPw) }
            },
            onDismiss = { showChangePassword = false }
        )
    }
}

//修改主密码对话框: 旧密码/新密码/确认
@Composable
private fun ChangePasswordDialog(
    onConfirm: (oldPassword: String, newPassword: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "修改主密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteTextField(
                    value = oldPw,
                    onValueChange = { oldPw = it },
                    label = "旧主密码",
                    modifier = Modifier.fillMaxWidth(),
                    password = true
                )
                NoteTextField(
                    value = newPw,
                    onValueChange = { newPw = it },
                    label = "新主密码(至少4位)",
                    modifier = Modifier.fillMaxWidth(),
                    password = true
                )
                NoteTextField(
                    value = confirmPw,
                    onValueChange = { confirmPw = it },
                    label = "确认新主密码",
                    modifier = Modifier.fillMaxWidth(),
                    password = true
                )
                Text(
                    text = "修改后指纹需要重新启用(会自动引导)。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    oldPw.isBlank() || newPw.isBlank() ->
                        Toast.makeText(context, "请填写完整", Toast.LENGTH_SHORT).show()
                    newPw.length < 4 ->
                        Toast.makeText(context, "新主密码至少4位", Toast.LENGTH_SHORT).show()
                    newPw != confirmPw ->
                        Toast.makeText(context, "两次输入的新密码不一致", Toast.LENGTH_SHORT).show()
                    else -> {
                        onConfirm(oldPw, newPw)
                        onDismiss()
                    }
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
@Composable
private fun ExportDialog(
    entries: List<NoteEntry>,
    onUnlock: suspend (String) -> Map<String, String>?,
    onDismiss: () -> Unit,
    onExport: (name: String, password: String, plaintext: Boolean) -> Unit
) {
    val context = LocalContext.current
    //名称自动生成: 备忘录备份_yyyyMMdd_HHmm(仍可手动修改)
    var name by remember {
        mutableStateOf(
            "备忘录备份_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        )
    }
    var password by remember { mutableStateOf("") }
    var plaintext by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "导出备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "压缩包名称",
                    modifier = Modifier.fillMaxWidth()
                )
                NoteTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "压缩包密码",
                    modifier = Modifier.fillMaxWidth(),
                    password = true
                )
                //明文导出开关: 着色容器常驻警示色，更醒目
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { plaintext = !plaintext }
                        .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "明文导出",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = if (plaintext) "所有密码和2FA密钥将以明文保存"
                            else "开启后导出可读TXT(用于查看)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Switch(checked = plaintext, onCheckedChange = { plaintext = it })
                }
                Text(
                    text = if (plaintext) {
                        "注意: 明文文件包含全部密码和2FA密钥，请务必妥善保管。"
                    } else {
                        "导出的是加密备份文件，解压和恢复都需要这个密码，请牢记。"
                    },
                    fontSize = 12.sp,
                    color = if (plaintext) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (password.isBlank()) {
                    Toast.makeText(context, "请设置压缩包密码", Toast.LENGTH_SHORT).show()
                } else {
                    onExport(name.trim(), password, plaintext)
                }
            }) { Text("导出") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
