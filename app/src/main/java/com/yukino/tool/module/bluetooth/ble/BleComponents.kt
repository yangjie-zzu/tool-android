package com.yukino.tool.module.bluetooth.ble

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/*
 * 可复用选择器组件: 参数输入"手填 + 从调试结果选择"两种方式。
 * DevicePickerDialog(扫描列表点选回填) / CharacteristicPickerDialog(连接后服务树点选回填 svc+chr) / HexInputField。
 */

/** HEX 输入框: 输入时宽松校验, 非空且解析失败标红提示 */
@Composable
fun HexInputField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it) },
        label = { Text(label) },
        isError = value.isNotEmpty() && parseHex(value) == null,
        supportingText = if (value.isNotEmpty() && parseHex(value) == null) {
            { Text("格式: 偶数位十六进制, 可带 : 空格 分隔", color = MaterialTheme.colorScheme.error) }
        } else null,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 设备选择对话框: 实时扫描全部设备, 点选回调 */
@Composable
fun DevicePickerDialog(
    permissions: BlePermissions,
    onPick: (DiscoveredDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var scanning by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = {
        job?.cancel()
        onDismiss()
    }) {
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("扫描周围设备…", fontWeight = FontWeight.Bold)
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { dev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    job?.cancel()
                                    onPick(dev)
                                }
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(dev.name ?: "(无名)", modifier = Modifier.weight(1f))
                            Text("${dev.rssi}dBm ${dev.serviceUuids.firstOrNull() ?: ""}", fontSize = 12.sp)
                        }
                    }
                    if (devices.isEmpty() && err.isEmpty()) Text(if (scanning) "扫描中…" else "点\"开始扫描\"查找设备", fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !scanning,
                        onClick = {
                            scope.launch {
                                err = ""
                                devices = emptyList()
                                scanning = true
                                try {
                                    if (!permissions.ensurePermissions()) throw BleOpException("蓝牙权限未授予")
                                    val adapter = permissions.adapter() ?: throw BleOpException("设备不支持蓝牙")
                                    if (!permissions.ensureEnabled()) throw BleOpException("蓝牙未开启")
                                    job = launch {
                                        runCatching {
                                            BleScanner(adapter).scan().collect { devices = it }
                                        }.onFailure { if (devices.isEmpty()) err = it.message ?: "扫描失败" }
                                    }
                                    job?.join()
                                } catch (e: Exception) {
                                    err = e.message ?: "扫描失败"
                                } finally {
                                    scanning = false
                                }
                            }
                        }
                    ) { Text("开始扫描") }
                    OutlinedButton(onClick = {
                        job?.cancel()
                        onDismiss()
                    }) { Text("关闭") }
                }
            }
        }
    }
}

/** 特征选择对话框: 扫描连接第一个发现的设备 → 服务/特征树点选, 回调 (svc, chr, props) */
@Composable
fun CharacteristicPickerDialog(
    permissions: BlePermissions,
    onPick: (svc: String, chr: String, props: Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<ServiceTree?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var selectedSvc by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("从设备读取服务/特征", fontWeight = FontWeight.Bold)
                Text("将扫描并连接第一个发现的设备(无过滤), 取其服务树", fontSize = 12.sp)
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                tree?.let { t ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        t.services.filter { it.characteristics.isNotEmpty() }.forEach { svc ->
                            Text(
                                "服务 ${svc.uuid}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSvc = svc.uuid }
                                    .padding(vertical = 2.dp)
                            )
                            if (selectedSvc == svc.uuid) {
                                svc.characteristics.forEach { chr ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onPick(svc.uuid, chr.uuid, chr.properties) }
                                            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("特征 ${chr.uuid}", fontSize = 13.sp)
                                        Text(
                                            chr.properties.joinToString("/"),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                err = ""
                                tree = null
                                try {
                                    if (!permissions.ensurePermissions()) throw BleOpException("蓝牙权限未授予")
                                    val adapter = permissions.adapter() ?: throw BleOpException("设备不支持蓝牙")
                                    if (!permissions.ensureEnabled()) throw BleOpException("蓝牙未开启")
                                    val dev = withTimeout(15000) {
                                        BleScanner(adapter).scan().first { it.isNotEmpty() }.first()
                                    }
                                    @Suppress("DEPRECATION")
                                    val remote = adapter.getRemoteDevice(dev.mac)
                                    val client = BleClient(permissions.appContext)
                                    val t = client.connect(remote)
                                    client.disconnect()
                                    tree = t
                                } catch (e: Exception) {
                                    err = e.message ?: "连接失败"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text(if (busy) "读取中…" else "读取设备") }
                    OutlinedButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
