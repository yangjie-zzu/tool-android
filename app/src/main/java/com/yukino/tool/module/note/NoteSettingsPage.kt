package com.yukino.tool.module.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// 设置页: 主密码/指纹的按需管理
@Composable
internal fun SettingsPage(
    onSetupMaster: () -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit
) {
    val context = LocalContext.current
    val masterReady = NoteCrypto.masterReady(context)
    val bioEnabled = NoteCrypto.biometricEnabled(context)
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
        }
    }
}
