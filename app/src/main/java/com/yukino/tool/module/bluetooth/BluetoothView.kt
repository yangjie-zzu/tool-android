package com.yukino.tool.module.bluetooth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yukino.tool.components.Title
import com.yukino.tool.module.bluetooth.ble.BleClient
import com.yukino.tool.module.bluetooth.ble.BleOpException
import com.yukino.tool.module.bluetooth.ble.BlePermissions
import com.yukino.tool.module.bluetooth.ble.BleScanner
import com.yukino.tool.module.bluetooth.ble.CharacteristicPickerDialog
import com.yukino.tool.module.bluetooth.ble.DeviceMatch
import com.yukino.tool.module.bluetooth.ble.DevicePickerDialog
import com.yukino.tool.module.bluetooth.ble.DiscoveredDevice
import com.yukino.tool.module.bluetooth.ble.ExecEvent
import com.yukino.tool.module.bluetooth.ble.HexInputField
import com.yukino.tool.module.bluetooth.ble.ListenParams
import com.yukino.tool.module.bluetooth.ble.Preset
import com.yukino.tool.module.bluetooth.ble.PresetExecutor
import com.yukino.tool.module.bluetooth.ble.PresetStore
import com.yukino.tool.module.bluetooth.ble.PresetTemplates
import com.yukino.tool.module.bluetooth.ble.ReadParams
import com.yukino.tool.module.bluetooth.ble.ServiceTree
import com.yukino.tool.module.bluetooth.ble.WriteCommand
import com.yukino.tool.module.bluetooth.ble.WriteParams
import com.yukino.tool.module.bluetooth.ble.parseHex
import com.yukino.tool.module.bluetooth.ble.toAscii
import com.yukino.tool.module.bluetooth.ble.toHex
import com.yukino.tool.util.IntentLauncher
import com.yukino.tool.util.PermissionRequester
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * 蓝牙模块: 三种功能模板(发指令/读数据/监听数据) + 一键预设(执行时可改参数) + 全量调试页。
 * 页面内导航用简单的 page 状态: 0=预设列表 1=编辑/新建 2=调试页; 执行面板为对话框。
 * 旧"关灯"硬编码逻辑已由内置"关灯"预设(PresetStore.seedIfEmpty)等价替代。
 */

class BluetoothActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = BlePermissions(
            applicationContext,
            PermissionRequester(this),
            IntentLauncher(this)
        )
        val executor = PresetExecutor(applicationContext, permissions)
        setContent {
            com.yukino.tool.ui.theme.ToolTheme {
                BluetoothView(permissions, executor)
            }
        }
    }
}

private fun templateLabel(t: String): String = when (t) {
    PresetTemplates.WRITE -> "发指令"
    PresetTemplates.READ -> "读数据"
    PresetTemplates.LISTEN -> "监听"
    else -> t
}

@Composable
fun BluetoothView(permissions: BlePermissions, executor: PresetExecutor) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }
    var editing by remember { mutableStateOf<Preset?>(null) }
    var executing by remember { mutableStateOf<Preset?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val allPresets = remember(refreshKey) {
        PresetStore.seedIfEmpty(context)
        PresetStore.list(context)
    }

    // 子页(编辑/调试)按系统返回键回预设列表, 而不是退出整个蓝牙 Activity
    BackHandler(enabled = page != 0) { page = 0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            when (page) {
                0 -> Title("蓝牙功能")
                1 -> Title(if (editing == null) "新建预设" else "编辑预设")
                2 -> Title("蓝牙调试")
            }
            if (page == 1) {
                OutlinedButton(onClick = { page = 0 }) { Text("返回") }
            }
        }
        when (page) {
            0 -> PresetListPage(
                presets = allPresets,
                onExecute = { executing = it },
                onEdit = { editing = it; page = 1 },
                onNew = { editing = null; page = 1 },
                onDelete = {
                    PresetStore.delete(context, it.id)
                    refreshKey++
                },
                onDebug = { page = 2 }
            )
            1 -> PresetEditPage(
                editing = editing,
                permissions = permissions,
                onSave = { name, template, params ->
                    val e = editing
                    if (e == null) PresetStore.add(context, name, template, params)
                    else PresetStore.update(context, e.id, name, params)
                    refreshKey++
                    page = 0
                }
            )
            2 -> DebugPage(permissions)
        }
    }

    executing?.let { preset ->
        ExecuteDialog(
            preset = preset,
            executor = executor,
            onDismiss = {
                executor.stop()
                executing = null
                refreshKey++
            },
            onSaveBack = { p ->
                PresetStore.update(context, preset.id, preset.name, p)
                refreshKey++
            }
        )
    }
}

// —— 预设列表页 ——

@Composable
private fun PresetListPage(
    presets: List<Preset>,
    onExecute: (Preset) -> Unit,
    onEdit: (Preset) -> Unit,
    onNew: () -> Unit,
    onDelete: (Preset) -> Unit,
    onDebug: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(presets, key = { it.id }) { preset ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onExecute(preset) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(preset.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("[${templateLabel(preset.template)}]", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(summarize(preset.params), fontSize = 12.sp, color = Color.Gray)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onExecute(preset) }) { Text("执行") }
                            OutlinedButton(onClick = { onEdit(preset) }) { Text("编辑") }
                            OutlinedButton(onClick = { onDelete(preset) }) { Text("删除") }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onNew) { Text("新建预设") }
            OutlinedButton(onClick = onDebug) { Text("调试") }
        }
    }
}

private fun summarize(params: com.yukino.tool.module.bluetooth.ble.PresetParams): String = when (params) {
    is WriteParams -> "设备:${matchText(params.match)} 特征:${params.svc}/${params.chr} 指令x${params.commands.size}"
    is ReadParams -> "设备:${matchText(params.match)} 特征:${params.svc}/${params.chr} 读${params.repeat}次"
    is ListenParams -> "设备:${matchText(params.match)} 特征:${params.svc}/${params.chr}"
    else -> ""
}

private fun matchText(m: DeviceMatch): String =
    if (m.matchType == "mac") "MAC ${m.mac}"
    else "UUID ${m.serviceUuid}${if (m.nameContains.isEmpty()) "" else " 名称含${m.nameContains}"}"

// —— 编辑页 ——

@Composable
private fun PresetEditPage(
    editing: Preset?,
    permissions: BlePermissions,
    onSave: (name: String, template: String, params: com.yukino.tool.module.bluetooth.ble.PresetParams) -> Unit
) {
    val wp0 = editing?.params as? WriteParams
    val rp0 = editing?.params as? ReadParams
    val lp0 = editing?.params as? ListenParams
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var template by remember(editing) { mutableStateOf(editing?.template ?: PresetTemplates.WRITE) }
    var matchType by remember(editing) { mutableStateOf(wp0?.match?.matchType ?: rp0?.match?.matchType ?: lp0?.match?.matchType ?: "uuid") }
    var mac by remember(editing) { mutableStateOf(wp0?.match?.mac ?: rp0?.match?.mac ?: lp0?.match?.mac ?: "") }
    var serviceUuid by remember(editing) { mutableStateOf(wp0?.match?.serviceUuid ?: rp0?.match?.serviceUuid ?: lp0?.match?.serviceUuid ?: "") }
    var nameContains by remember(editing) { mutableStateOf(wp0?.match?.nameContains ?: rp0?.match?.nameContains ?: lp0?.match?.nameContains ?: "") }
    var svc by remember(editing) { mutableStateOf(wp0?.svc ?: rp0?.svc ?: lp0?.svc ?: "") }
    var chr by remember(editing) { mutableStateOf(wp0?.chr ?: rp0?.chr ?: lp0?.chr ?: "") }
    var commands by remember(editing) {
        mutableStateOf(wp0?.commands?.map { it.hex to it.delayMs } ?: listOf("" to 0))
    }
    var writeWithResp by remember(editing) { mutableStateOf(wp0?.writeWithResp ?: true) }
    var btOffAfter by remember(editing) { mutableStateOf(wp0?.btOffAfter ?: false) }
    var repeat by remember(editing) { mutableStateOf((rp0?.repeat ?: 1).toString()) }
    var intervalMs by remember(editing) { mutableStateOf((rp0?.intervalMs ?: 0).toString()) }
    var durationMs by remember(editing) { mutableStateOf((lp0?.durationMs ?: 0L).toString()) }

    var showDevicePicker by remember { mutableStateOf(false) }
    var showCharPicker by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("预设名称") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("模板:")
            listOf(PresetTemplates.WRITE to "发指令", PresetTemplates.READ to "读数据", PresetTemplates.LISTEN to "监听").forEach { (t, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { template = t }) {
                    RadioButton(selected = template == t, onClick = { template = t })
                    Text(label)
                }
            }
        }
        Text("设备匹配", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { matchType = "uuid" }) {
            RadioButton(selected = matchType == "uuid", onClick = { matchType = "uuid" })
            Text("按广播 UUID")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { matchType = "mac" }) {
            RadioButton(selected = matchType == "mac", onClick = { matchType = "mac" })
            Text("按 MAC")
        }
        if (matchType == "uuid") {
            OutlinedTextField(value = serviceUuid, onValueChange = { serviceUuid = it }, label = { Text("广播服务 UUID(如 FFE0)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = nameContains, onValueChange = { nameContains = it }, label = { Text("名称包含(可空)") }, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(value = mac, onValueChange = { mac = it }, label = { Text("MAC 地址") }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showDevicePicker = true }) { Text("扫描选择设备") }
        }

        Text("目标特征", fontWeight = FontWeight.Bold)
        OutlinedTextField(value = svc, onValueChange = { svc = it }, label = { Text("服务 UUID(如 FFF0)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = chr, onValueChange = { chr = it }, label = { Text("特征 UUID(如 FFF2)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showCharPicker = true }) { Text("从设备读取") }
        }

        when (template) {
            PresetTemplates.WRITE -> {
                Text("指令列表(顺序执行)", fontWeight = FontWeight.Bold)
                commands.forEachIndexed { i, cmd ->
                    HexInputField(
                        label = "指令 ${i + 1} HEX",
                        value = cmd.first,
                        onChange = { v -> commands = commands.toMutableList().also { it[i] = v to cmd.second } }
                    )
                    OutlinedTextField(
                        value = cmd.second.toString(),
                        onValueChange = { v -> commands = commands.toMutableList().also { it[i] = cmd.first to (v.toIntOrNull() ?: 0) } },
                        label = { Text("与下条间隔 ms") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = { commands = commands.filterIndexed { idx, _ -> idx != i } }) { Text("删除指令 ${i + 1}") }
                }
                OutlinedButton(onClick = { commands = commands + ("" to 0) }) { Text("添加指令") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = writeWithResp, onCheckedChange = { writeWithResp = it })
                    Text("带响应写入(可靠)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = btOffAfter, onCheckedChange = { btOffAfter = it })
                    Text("执行完关闭蓝牙")
                }
            }
            PresetTemplates.READ -> {
                OutlinedTextField(value = repeat, onValueChange = { repeat = it }, label = { Text("读取次数") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = intervalMs, onValueChange = { intervalMs = it }, label = { Text("间隔 ms") }, modifier = Modifier.fillMaxWidth())
            }
            PresetTemplates.LISTEN -> {
                OutlinedTextField(value = durationMs, onValueChange = { durationMs = it }, label = { Text("监听时长 ms(0=手动停)") }, modifier = Modifier.fillMaxWidth())
            }
        }

        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                err = ""
                if (name.isBlank()) { err = "名称不能为空"; return@Button }
                if (template == PresetTemplates.WRITE && commands.any { it.first.isNotBlank() && parseHex(it.first) == null }) {
                    err = "存在格式错误的指令 HEX"; return@Button
                }
                val match = DeviceMatch(matchType, mac, serviceUuid, nameContains)
                val params = when (template) {
                    PresetTemplates.WRITE -> WriteParams(
                        match, svc, chr,
                        commands.filter { it.first.isNotBlank() }.map { WriteCommand(it.first, it.second) },
                        writeWithResp, btOffAfter
                    )
                    PresetTemplates.READ -> ReadParams(match, svc, chr, repeat.toIntOrNull() ?: 1, intervalMs.toIntOrNull() ?: 0)
                    else -> ListenParams(match, svc, chr, durationMs.toLongOrNull() ?: 0L)
                }
                onSave(name.trim(), template, params)
            }
        ) { Text("保存") }
    }

    if (showDevicePicker) {
        DevicePickerDialog(
            permissions = permissions,
            onPick = { dev ->
                matchType = "mac"
                mac = dev.mac
                if (serviceUuid.isEmpty()) serviceUuid = dev.serviceUuids.firstOrNull() ?: ""
                showDevicePicker = false
            },
            onDismiss = { showDevicePicker = false }
        )
    }
    if (showCharPicker) {
        CharacteristicPickerDialog(
            permissions = permissions,
            onPick = { s, c, _ ->
                svc = s
                chr = c
                showCharPicker = false
            },
            onDismiss = { showCharPicker = false }
        )
    }
}

// —— 执行面板 ——

@Composable
private fun ExecuteDialog(
    preset: Preset,
    executor: PresetExecutor,
    onDismiss: () -> Unit,
    onSaveBack: (com.yukino.tool.module.bluetooth.ble.PresetParams) -> Unit
) {
    val events by executor.events().collectAsState(initial = emptyList())
    val running by executor.isRunning().collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var saveBack by remember { mutableStateOf(false) }
    var wp by remember { mutableStateOf(preset.params as? WriteParams) }
    var rp by remember { mutableStateOf(preset.params as? ReadParams) }
    var lp by remember { mutableStateOf(preset.params as? ListenParams) }

    Dialog(onDismissRequest = { if (!running) onDismiss() }) {
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("执行: ${preset.name}", fontWeight = FontWeight.Bold)
                if (!running) {
                    when (preset.template) {
                        PresetTemplates.WRITE -> wp?.let { p ->
                            p.commands.forEachIndexed { i, cmd ->
                                HexInputField("指令 ${i + 1} HEX", cmd.hex) { hex ->
                                    wp = p.copy(commands = p.commands.toMutableList().also { it[i] = cmd.copy(hex = hex) })
                                }
                                OutlinedTextField(
                                    value = cmd.delayMs.toString(),
                                    onValueChange = { v ->
                                        wp = p.copy(commands = p.commands.toMutableList().also { it[i] = cmd.copy(delayMs = v.toIntOrNull() ?: 0) })
                                    },
                                    label = { Text("与下条间隔 ms") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = p.btOffAfter, onCheckedChange = { wp = p.copy(btOffAfter = it) })
                                Text("执行完关闭蓝牙")
                            }
                        }
                        PresetTemplates.READ -> rp?.let { p ->
                            OutlinedTextField(value = p.repeat.toString(), onValueChange = { rp = p.copy(repeat = it.toIntOrNull() ?: 1) }, label = { Text("读取次数") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = p.intervalMs.toString(), onValueChange = { rp = p.copy(intervalMs = it.toIntOrNull() ?: 0) }, label = { Text("间隔 ms") }, modifier = Modifier.fillMaxWidth())
                        }
                        else -> {}
                    }
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    events.forEach { ev ->
                        when (ev) {
                            is ExecEvent.Step -> Text(ev.text, fontSize = 13.sp)
                            is ExecEvent.Data -> {
                                Text("HEX: ${ev.hex}", fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                Text("文本: ${ev.ascii}", fontSize = 13.sp)
                            }
                            is ExecEvent.Done -> Text(
                                ev.message,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (ev.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!running) {
                        Button(onClick = {
                            val params = wp ?: rp ?: lp ?: preset.params
                            scope.launch { executor.execute(preset.name, preset.template, params) }
                        }) { Text("执行") }
                        if (saveBack) {
                            Button(onClick = {
                                onSaveBack(wp ?: rp ?: lp ?: preset.params)
                                saveBack = false
                            }) { Text("确认存回") }
                        } else {
                            OutlinedButton(onClick = { saveBack = true }) { Text("存回预设") }
                        }
                    } else {
                        OutlinedButton(onClick = { executor.stop() }) { Text("停止") }
                    }
                    OutlinedButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

// —— 调试页 ——

@Composable
private fun DebugPage(permissions: BlePermissions) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var err by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var tree by remember { mutableStateOf<ServiceTree?>(null) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var expandedSvc by remember { mutableStateOf<String?>(null) }
    var chrInput by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<BleClient?>(null) }
    var connectingMac by remember { mutableStateOf<String?>(null) }

    fun appendLog(s: String) {
        log = (log + s).takeLast(100)
    }

    if (selected == null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
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
                                scanJob = launch {
                                    runCatching {
                                        BleScanner(adapter).scan().collect { devices = it }
                                    }.onFailure { if (devices.isEmpty()) err = it.message ?: "扫描失败" }
                                }
                                scanJob?.join()
                            } catch (e: Exception) {
                                err = e.message ?: "扫描失败"
                            } finally {
                                scanning = false
                            }
                        }
                    }
                ) { Text(if (scanning) "扫描中…" else "开始扫描") }
                if (scanning) OutlinedButton(onClick = { scanJob?.cancel() }) { Text("停止") }
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(devices, key = { it.mac }) { dev ->
                    val connectingThis = connectingMac == dev.mac
                    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = connectingMac == null) {
                        // 连接前先停扫(Android 上扫描进行中发起连接易失败)
                        scanJob?.cancel()
                        connectingMac = dev.mac
                        err = ""
                        scope.launch {
                            try {
                                val adapter = permissions.adapter() ?: throw BleOpException("设备不支持蓝牙")
                                @Suppress("DEPRECATION")
                                val remote = adapter.getRemoteDevice(dev.mac)
                                val c = BleClient(permissions.appContext)
                                tree = c.connect(remote)
                                client = c
                                selected = dev
                                log = emptyList()
                                appendLog("已连接 ${dev.name ?: dev.mac}")
                            } catch (e: Exception) {
                                err = e.message ?: "连接失败"
                            } finally {
                                connectingMac = null
                                scanning = false
                            }
                        }
                    }) {
                        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(dev.name ?: "(无名)")
                                    if (!dev.connectable) {
                                        Text("不可连", fontSize = 11.sp, color = Color.Gray,
                                            modifier = Modifier.padding(start = 2.dp, top = 1.dp, bottom = 1.dp, end = 4.dp))
                                    }
                                }
                                Text(dev.mac, fontSize = 12.sp, color = Color.Gray)
                            }
                            if (connectingThis) {
                                Text("连接中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("${dev.rssi}dBm", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    client?.disconnect()
                    client = null
                    selected = null
                    tree = null
                }) { Text("断开返回") }
            }
            Text(
                "设备: ${selected?.name ?: "(无名)"} ${selected?.mac}" +
                    if (client?.isBonded() == true) " (已绑定)" else "",
                fontSize = 13.sp
            )
            tree?.services?.forEach { svc ->
                Text(
                    "服务 ${svc.uuid} (${svc.characteristics.size} 个数据点)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedSvc = if (expandedSvc == svc.uuid) null else svc.uuid }
                        .padding(vertical = 2.dp)
                )
                if (expandedSvc == svc.uuid) {
                    svc.characteristics.forEach { chr ->
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(chr.uuid, fontSize = 13.sp)
                                Text(chr.properties.joinToString("/"), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if ("read" in chr.properties) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            try {
                                                val v = client?.read(svc.uuid, chr.uuid) ?: return@launch
                                                appendLog("读 ${chr.uuid}: ${toHex(v)} | ${toAscii(v)}")
                                            } catch (e: Exception) {
                                                appendLog("读 ${chr.uuid} 失败: ${e.message}")
                                            }
                                        }
                                    }) { Text("读", fontSize = 12.sp) }
                                }
                                if ("write" in chr.properties || "writeNoResp" in chr.properties) {
                                    OutlinedButton(onClick = { chrInput = chr.uuid }) { Text("写", fontSize = 12.sp) }
                                }
                                if ("notify" in chr.properties || "indicate" in chr.properties) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            try {
                                                val c = client ?: return@launch
                                                val mode = c.subscribe(svc.uuid, chr.uuid) { bytes ->
                                                    appendLog("收 ${chr.uuid}: ${toHex(bytes)} | ${toAscii(bytes)}")
                                                }
                                                appendLog("已订阅 ${chr.uuid}(${if (mode) "indicate" else "notify"})")
                                            } catch (e: Exception) {
                                                appendLog("订阅 ${chr.uuid} 失败: ${e.message}")
                                            }
                                        }
                                    }) { Text("订阅", fontSize = 12.sp) }
                                }
                            }
                            if (chrInput == chr.uuid) {
                                var text by remember(chr.uuid) { mutableStateOf("") }
                                var asAscii by remember(chr.uuid) { mutableStateOf(false) }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedTextField(
                                        value = text,
                                        onValueChange = { text = it },
                                        label = { Text(if (asAscii) "ASCII 内容" else "HEX 内容") },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Checkbox(checked = asAscii, onCheckedChange = { asAscii = it })
                                    Text("ASCII", fontSize = 12.sp)
                                }
                                Button(onClick = {
                                    scope.launch {
                                        try {
                                            val bytes = if (asAscii) text.toByteArray(Charsets.US_ASCII)
                                            else parseHex(text) ?: throw BleOpException("HEX 格式错误")
                                            val st = client?.write(svc.uuid, chr.uuid, bytes, "write" in chr.properties)
                                            appendLog("写 ${chr.uuid}: ${toHex(bytes)} status=$st")
                                            chrInput = ""
                                        } catch (e: Exception) {
                                            appendLog("写 ${chr.uuid} 失败: ${e.message}")
                                        }
                                    }
                                }) { Text("发送", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
            Text("日志", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(log) { line ->
                    Text(line, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}
