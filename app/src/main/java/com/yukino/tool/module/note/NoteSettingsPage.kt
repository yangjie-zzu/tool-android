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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    bioVersion: Int = 0,
    onUnlock: suspend (String) -> Map<String, String>?,
    onSetupMaster: () -> Unit,
    onChangeMasterPassword: suspend (String, String) -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExport by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    // 指纹/主密码状态是文件读取的普通值，停用等落盘操作本身不触发重组；
    // 以bioVersion为key缓存，NoteActivity在启用/停用落盘后自增版本，这里随之重读刷新
    val masterReady = remember(bioVersion) { NoteCrypto.masterReady(context) }
    val bioEnabled = remember(bioVersion) { NoteCrypto.biometricEnabled(context) }
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
                    Text(text = "导出")
                    Text(
                        text = "备份或导出为Markdown，可分享到电脑留存",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showExport = true }) { Text(text = "导出") }
            }
        }
    }
    //导出对话框: 名称/密码/模式(备份|加密导出|明文导出)
    if (showExport) {
        ExportDialog(
            entries = entries,
            onUnlock = onUnlock,
            onDismiss = { showExport = false },
            onExport = { name, password, mode ->
                scope.launch {
                    try {
                        val file = when (mode) {
                            NoteExport.MODE_ENCRYPTED_MD -> {
                                //加密导出内含明文，必须先通过认证
                                val secrets = onUnlock("导出加密明文") ?: return@launch
                                val md = withContext(Dispatchers.IO) {
                                    NoteExport.buildPlaintextMarkdown(entries, secrets)
                                }
                                withContext(Dispatchers.IO) {
                                    NoteExport.exportEncryptedMdZip(context, name, password, md)
                                }
                            }
                            NoteExport.MODE_PLAIN_MD -> {
                                //明文导出包含全部密码和2FA密钥，必须先通过认证
                                val secrets = onUnlock("导出明文") ?: return@launch
                                val md = withContext(Dispatchers.IO) {
                                    NoteExport.buildPlaintextMarkdown(entries, secrets)
                                }
                                withContext(Dispatchers.IO) {
                                    NoteExport.exportPlaintextMd(context, name, md)
                                }
                            }
                            else -> withContext(Dispatchers.IO) {
                                NoteExport.exportEncryptedBackupZip(context, name, password)
                            }
                        }
                        showExport = false
                        NoteExport.share(context, file)
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
    onExport: (name: String, password: String, mode: Int) -> Unit
) {
    val context = LocalContext.current
    //名称自动生成: 备忘录备份_yyyyMMdd_HHmm(仍可手动修改)
    var name by remember {
        mutableStateOf(
            "备忘录备份_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        )
    }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(NoteExport.MODE_BACKUP) }
    val needPassword = mode != NoteExport.MODE_PLAIN_MD

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "导出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = if (mode == NoteExport.MODE_PLAIN_MD) "文件名称" else "压缩包名称",
                    modifier = Modifier.fillMaxWidth()
                )
                //明文.md直接分享无需密码;两种加密ZIP都需要密码
                if (needPassword) {
                    NoteTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "压缩包密码",
                        modifier = Modifier.fillMaxWidth(),
                        password = true
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ExportModeOption(
                        title = "备份",
                        desc = "加密ZIP(原始数据文件)，恢复需导回本应用",
                        selected = mode == NoteExport.MODE_BACKUP,
                        onClick = { mode = NoteExport.MODE_BACKUP }
                    )
                    ExportModeOption(
                        title = "加密导出",
                        desc = "加密ZIP内含可读Markdown(.md)，解压即可查看",
                        selected = mode == NoteExport.MODE_ENCRYPTED_MD,
                        onClick = { mode = NoteExport.MODE_ENCRYPTED_MD }
                    )
                    ExportModeOption(
                        title = "明文导出",
                        desc = "Markdown(.md)直接分享，含全部密码和2FA密钥",
                        selected = mode == NoteExport.MODE_PLAIN_MD,
                        onClick = { mode = NoteExport.MODE_PLAIN_MD }
                    )
                }
                Text(
                    text = when (mode) {
                        NoteExport.MODE_ENCRYPTED_MD ->
                            "注意: Markdown内含全部密码和2FA密钥，解压密码请牢记。"
                        NoteExport.MODE_PLAIN_MD ->
                            "注意: 明文文件包含全部密码和2FA密钥，请务必妥善保管。"
                        else ->
                            "导出的是加密备份文件，解压和恢复都需要这个密码，请牢记。"
                    },
                    fontSize = 12.sp,
                    color = if (mode == NoteExport.MODE_PLAIN_MD) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (needPassword && password.isBlank()) {
                    Toast.makeText(context, "请设置压缩包密码", Toast.LENGTH_SHORT).show()
                } else {
                    onExport(name.trim(), password, mode)
                }
            }) { Text("导出") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ExportModeOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
