package com.yukino.tool.module.note

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 编辑器: 字段完全自定义。加密字段的值要明文编辑需先点击输入框通过认证解密；
 * 加密值留空保存 = 清空该值。2FA字段的值是Base32密钥(支持扫码或粘贴otpauth链接)。
 */
@Composable
internal fun EditorPage(
    entry: NoteEntry,
    isNew: Boolean,
    secrets: Map<String, String>,
    onUnlock: suspend (String) -> Map<String, String>?,
    onSave: (NoteEntry, Map<String, String>, Boolean) -> Unit,
    onDelete: ((NoteEntry) -> Unit)?,
    onCancel: () -> Unit
) {
    // 初始值: 明文字段用原值；加密字段用缓存(验证过)否则空串
    var fields by remember {
        mutableStateOf(
            entry.fields.map { field ->
                field.copy(value = if (field.secret) secrets["${entry.id}:${field.key}"] ?: "" else field.value)
            }
        )
    }

    // 加密内容是否被改动过(输入加密值/改加密字段名/切换加密开关/删除加密字段)。
    // 只有改动了加密内容，保存才需要验证主密码重写加密段；没动过则明文段和加密段各自独立保存
    var secretTouched by remember { mutableStateOf(false) }

    // 加密值输入框的"待验证遮罩"是否被用户取消过(取消后允许直接盲输新值，不再弹认证)
    var lockDismissed by remember { mutableStateOf(false) }

    // 删除整条记录的二次确认
    var showDeleteRecord by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun submit() {
        val clean = fields.filter { it.key.isNotBlank() || it.value.isNotBlank() }
        val newSecrets = mutableMapOf<String, String>()
        var totpInvalid = false
        clean.forEach { field ->
            if (field.secret && field.value.isNotBlank()) {
                if (field.totp) {
                    //2FA字段: 规整密钥(支持Base32或otpauth链接)；非法则中止保存
                    val normalized = Totp.normalizeSecret(field.value)
                    if (normalized == null) {
                        totpInvalid = true
                    } else {
                        newSecrets["${entry.id}:${field.key}"] = normalized
                    }
                } else {
                    newSecrets["${entry.id}:${field.key}"] = field.value
                }
            }
        }
        if (totpInvalid) {
            Toast.makeText(context, "2FA密钥格式无效(需Base32或otpauth链接)", Toast.LENGTH_LONG).show()
            return
        }
        onSave(entry.copy(fields = clean), newSecrets, secretTouched)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            VaultTopBar(if (isNew) "新增" else "编辑")
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    //padding放在verticalScroll之后: 间距属于滚动内容，会跟着滑走而不是固定在视口上
                    .padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            fields.forEachIndexed { index, field ->
                //每张卡片自有的弹窗开关(弹窗是模态的，打开期间列表不会重排，按位置记忆安全)
                var showConfig by remember { mutableStateOf(false) }
                var showDelete by remember { mutableStateOf(false) }
                var scanning by remember { mutableStateOf(false) }
                //加密字段输入掩码开关: 默认掩码，点眼睛临时切明文(仅编辑显示层，值始终明文)
                var showPlain by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                    //阴影让卡片在深色背景上有立体感
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        //顶部留白要容纳悬浮在输入框边框上的label(上凸约8dp)
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        //值输入框(label用字段名)。加密值未解密时显示占位符(虚假值，纯展示不可编辑)，
                        //盖一层点击层: 点击弹认证，解密成功预填全部加密字段明文；
                        //取消认证则移除遮罩，允许直接盲输新值
                        val hasStored = entry.fields
                            .firstOrNull { it.secret && it.key == field.key }?.value?.isNotEmpty() == true
                        val locked = field.secret && !lockDismissed && hasStored &&
                            secrets["${entry.id}:${field.key}"] == null
                        Box(modifier = Modifier.fillMaxWidth()) {
                            FieldValueInput(
                                label = field.key.ifBlank { "值" },
                                value = if (locked) "••••••••" else field.value,
                                onValueChange = {
                                    if (field.secret) secretTouched = true
                                    fields = fields.toMutableList().also { list ->
                                        list[index] = field.copy(value = it)
                                    }
                                },
                                onLabelClick = { showConfig = true },
                                secret = field.secret && !locked,
                                showPlain = showPlain,
                                onTogglePlain = if (field.secret && !locked) {
                                    { showPlain = !showPlain }
                                } else null,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (locked) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .pointerInput(Unit) {
                                            detectTapGestures {
                                                scope.launch {
                                                    val s = onUnlock("编辑 ${field.key}")
                                                    if (s == null) {
                                                        lockDismissed = true
                                                    } else {
                                                        fields = fields.map { f ->
                                                            if (f.secret) f.copy(value = s["${entry.id}:${f.key}"] ?: f.value) else f
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                )
                            }
                            //2FA字段: 输入框内右侧的扫码入口(悬浮在遮罩之上，锁定状态也可扫)
                            if (field.totp) {
                                Icon(
                                    imageVector = Icons.Rounded.QrCodeScanner,
                                    contentDescription = "扫码录入2FA密钥",
                                    tint = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        //加密字段有掩码切换眼睛图标时,扫码图标左移让位
                                        .padding(end = if (field.secret && !locked) 44.dp else 10.dp)
                                        .clip(CircleShape)
                                        .clickable { scanning = true }
                                        .padding(4.dp)
                                        .size(20.dp)
                                )
                            }
                        }
                        //第三行: 配置入口(点击弹配置)靠左，删除贴齐右缘
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { showConfig = true }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "字段配置",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                val flags = listOfNotNull(
                                    "加密".takeIf { field.secret },
                                    "2FA".takeIf { field.totp },
                                    "标题".takeIf { field.title },
                                    "预览".takeIf { field.preview }
                                )
                                Text(
                                    text = flags.joinToString(" · ").ifEmpty { "无配置" },
                                    style = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 14.sp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "删除字段",
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { showDelete = true }
                                    .padding(2.dp)
                                    .size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    //字段配置弹窗(卡片自有状态，无需index标记)
                    if (showConfig) {
                        FieldConfigDialog(
                            field = field,
                            onChange = { new ->
                                if (new.secret != field.secret) secretTouched = true
                                fields = fields.toMutableList().also { it[index] = new }
                            },
                            onRename = { newName ->
                                if (newName != field.key) {
                                    if (field.secret) secretTouched = true
                                    fields = fields.toMutableList().also { it[index] = field.copy(key = newName) }
                                }
                            },
                            onDismiss = { showConfig = false }
                        )
                    }
                    //删除字段确认弹窗
                    if (showDelete) {
                        AlertDialog(
                            onDismissRequest = { showDelete = false },
                            title = { Text(text = "删除字段") },
                            text = { Text(text = "确定删除字段「${field.key.ifBlank { "未命名字段" }}」吗？") },
                            confirmButton = {
                                TextButton(onClick = {
                                    if (field.secret) secretTouched = true
                                    fields = fields.filterIndexed { i, _ -> i != index }
                                    showDelete = false
                                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDelete = false }) { Text("取消") }
                            }
                        )
                    }
                    //扫码页(全屏Dialog覆盖，含底部操作栏)
                    if (scanning) {
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { scanning = false },
                            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            QrScanPage(
                                onResult = { raw ->
                                    val normalized = Totp.normalizeSecret(raw)
                                    if (normalized == null) {
                                        Toast.makeText(context, "二维码内容无法识别为2FA密钥", Toast.LENGTH_LONG).show()
                                    } else {
                                        fields = fields.toMutableList().also { list ->
                                            list[index] = field.copy(value = normalized)
                                        }
                                        secretTouched = true
                                    }
                                    scanning = false
                                },
                                onClose = { scanning = false }
                            )
                        }
                    }
                }
            }
            //添加字段块: 浅色背景与列表背景区分，点击添加
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.background)
                    .clickable {
                        fields = fields + NoteField()
                        //等新字段布局完成后滚动到底部
                        scope.launch {
                            delay(80)
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+ 添加字段",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
            }
        }
        //底部操作区: 浅色底板，顶部一条分隔线与滚动区区分
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            //顶部分隔线(贯通全宽)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 15.dp, end = 15.dp, bottom = 12.dp)
            ) {
                if (!isNew && onDelete != null) {
                    TextButton(onClick = { showDeleteRecord = true }) {
                        Text(text = "删除此记录", color = MaterialTheme.colorScheme.error)
                    }
                }
                //保存/取消放在底部，便于操作
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { submit() }, modifier = Modifier.weight(2f)) { Text("保存") }
                }
            }
        }
        //删除整条记录的二次确认
        if (showDeleteRecord) {
            AlertDialog(
                onDismissRequest = { showDeleteRecord = false },
                title = { Text(text = "删除此记录") },
                text = { Text(text = "确定删除整条记录吗？删除后不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteRecord = false
                        onDelete?.invoke(entry)
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteRecord = false }) { Text("取消") }
                }
            )
        }
    }
}
}

/*
 * 二维码扫描页: CameraX预览 + ML Kit本地识别(无需网络)，识别到二维码回调onResult(只回调一次)。
 * 首次使用会请求相机权限；拒绝后显示授权引导。
 */
@Composable
internal fun QrScanPage(
    onResult: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(android.Manifest.permission.CAMERA)
    }
    //扫码页拦截系统返回键: 只关闭扫码，不退出编辑器
    androidx.activity.compose.BackHandler(onBack = onClose)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            val lifecycleOwner = LocalLifecycleOwner.current
            val delivered = remember { mutableStateOf(false) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = androidx.camera.view.PreviewView(ctx)
                    val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient(
                        com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                            .build()
                    )
                    val analysis = androidx.camera.core.ImageAnalysis.Builder()
                        .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(androidx.core.content.ContextCompat.getMainExecutor(ctx)) { proxy ->
                        val media = proxy.image
                        if (media != null && !delivered.value) {
                            val input = com.google.mlkit.vision.common.InputImage.fromMediaImage(
                                media, proxy.imageInfo.rotationDegrees
                            )
                            scanner.process(input)
                                .addOnSuccessListener { codes ->
                                    val raw = codes.firstOrNull()?.rawValue
                                    if (raw != null && !delivered.value) {
                                        delivered.value = true
                                        onResult(raw)
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        } else {
                            proxy.close()
                        }
                    }
                    androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx).addListener({
                        val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx).get()
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                            androidx.camera.core.Preview.Builder().build()
                                .also { it.setSurfaceProvider(previewView.surfaceProvider) },
                            analysis
                        )
                    }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
            //关闭按钮
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Clear,
                    contentDescription = "关闭扫码",
                    tint = Color.White
                )
            }
            //提示文字
            Text(
                text = "将2FA二维码对准取景框",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
            )
        } else {
            //相机权限被拒: 引导去系统设置授权
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "扫码需要相机权限", color = Color.White, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                        )
                    }
                }) {
                    Text(text = "去设置授权", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onClose) { Text(text = "返回", color = Color.White) }
            }
        }
    }
}

/*
 * 带可点击label的值输入框: label(字段名+编辑图标)悬浮在边框上，整体可点击(用于打开字段配置弹窗)。
 * 外观仿 M3 OutlinedTextField: 圆角边框、聚焦变色、label悬浮并遮住边框线。
 */
@Composable
internal fun FieldValueInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onLabelClick: () -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    showPlain: Boolean = false,
    onTogglePlain: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    //配色对齐M3规范: 未聚焦边框=outline、聚焦=primary；label未聚焦=onSurfaceVariant、聚焦=primary
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val labelColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val hasTrailing = onTogglePlain != null
    Box(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (secret && !showPlain) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .border(1.dp, borderColor, MaterialTheme.shapes.small)
                .padding(top = 10.dp),
            decorationBox = { inner ->
                //右侧留出掩码切换图标的位置，避免文字压到图标下
                Box(
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = if (hasTrailing) 44.dp else 12.dp,
                        bottom = 8.dp
                    )
                ) { inner() }
            }
        )
        //label: 悬浮在边框上遮住边框线(固定18dp高度并上移半高，与边框线垂直居中)，整体可点击
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 12.dp, y = (-8).dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onLabelClick)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 14.sp),
                color = labelColor
            )
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "编辑字段配置",
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(14.dp),
                tint = labelColor
            )
        }
        //掩码切换(眼睛): 悬浮输入框右侧，编辑器外层的扫码图标会再往左让位
        if (onTogglePlain != null) {
            Icon(
                imageVector = if (showPlain) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = if (showPlain) "切换为掩码" else "切换为明文",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .clip(CircleShape)
                    .clickable { onTogglePlain() }
                    .padding(4.dp)
                    .size(20.dp)
            )
        }
    }
}

//字段配置弹窗: 改名/加密/标题/预览/2FA开关在这里
@Composable
private fun FieldConfigDialog(
    field: NoteField,
    onChange: (NoteField) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(field.key) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "字段配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NoteTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "字段名",
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "加密=查看/复制需验证；标题=列表标题；预览=列表中显示",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConfigSwitchRow(label = "加密存储(查看/复制需验证)", checked = field.secret) {
                    //关闭加密时2FA也随之关闭(2FA密钥必须加密存储)
                    onChange(field.copy(secret = it, totp = if (it) field.totp else false))
                }
                ConfigSwitchRow(label = "2FA动态码(值=Base32密钥)", checked = field.totp) {
                    //开启2FA强制加密存储
                    onChange(field.copy(totp = it, secret = it || field.secret))
                }
                ConfigSwitchRow(label = "作为条目标题", checked = field.title) {
                    onChange(field.copy(title = it))
                }
                ConfigSwitchRow(label = "在列表中预览", checked = field.preview) {
                    onChange(field.copy(preview = it))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onRename(name)
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ConfigSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
