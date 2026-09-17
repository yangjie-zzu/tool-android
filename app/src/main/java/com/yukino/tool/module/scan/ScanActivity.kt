package com.yukino.tool.module.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

// 扫码: 相机预览+ML Kit全格式识别; 识别成功去重保存后跳到记录页; 右上角入口进记录页
// 内部导航与解压缩统一: navigation-compose类型安全路由
class ScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ScanNavHost()
            }
        }
    }
}

@Serializable
object ScanRoute

@Serializable
object ScanHistoryRoute

@Composable
private fun ScanNavHost() {
    val context = LocalContext.current
    val navController = rememberNavController()
    // 记录列表状态: 扫描页保存后刷新, 记录页共享
    val historyItems = remember {
        mutableStateListOf<ScanItem>().apply { addAll(ScanStore.load(context)) }
    }

    NavHost(navController = navController, startDestination = ScanRoute) {
        composable<ScanRoute> {
            ScanCameraPage(
                onScanned = { content ->
                    //识别成功: 去重保存后跳转扫描记录; 右上角入口content为空串, 只跳转不保存
                    if (content.isNotEmpty()) {
                        val updated = ScanStore.save(context, content)
                        historyItems.clear()
                        historyItems.addAll(updated)
                    }
                    navController.navigate(ScanHistoryRoute) { launchSingleTop = true }
                }
            )
        }
        composable<ScanHistoryRoute> {
            ScanHistoryPage(items = historyItems, onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun ScanCameraPage(onScanned: (String) -> Unit) {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        //顶栏: 标题+右上角扫描记录入口
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.padding(start = 16.dp).size(22.dp)
            )
            Text(
                text = "扫一扫",
                fontSize = 18.sp,
                modifier = Modifier.weight(1f).padding(start = 8.dp, top = 14.dp, bottom = 14.dp)
            )
            IconButton(onClick = { onScanned("") }) {
                Icon(imageVector = Icons.Rounded.History, contentDescription = "扫描记录")
            }
        }

        when {
            hasPermission -> CameraScanArea(onResult = { content ->
                onScanned(content)
            })
            else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "未授予相机权限, 无法扫码", color = Color.White)
            }
        }
    }
}

//相机预览+逐帧识别; 取景框内识别到条码回调onResult(内容由上层保存)
@Composable
private fun CameraScanArea(onResult: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var lastContent by remember { mutableStateOf<String?>(null) }
    var lastTime by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                        .build()
                    val scanner = BarcodeScanning.getClient(options)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        scanFrame(scanner, image) { content ->
                            //防抖: 同一内容2秒内只触发一次, 避免连续识别重复保存跳转
                            val now = System.currentTimeMillis()
                            val debounced = content == lastContent && now - lastTime < 2000
                            if (!debounced) {
                                lastContent = content
                                lastTime = now
                                previewView.post { onResult(content) }
                            }
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )
        //取景框遮罩: offscreen合成后用Clear抠出中间镂空
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            val side = size.minDimension * 0.62f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f - size.height * 0.05f
            drawRect(color = Color.Black.copy(alpha = 0.47f))
            drawRect(
                color = Color.Black,
                topLeft = Offset(left, top),
                size = Size(side, side),
                blendMode = BlendMode.Clear
            )
            drawRect(
                topLeft = Offset(left, top),
                size = Size(side, side),
                color = Color.White,
                style = Stroke(width = 4f)
            )
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun scanFrame(
    scanner: BarcodeScanner,
    image: ImageProxy,
    onResult: (String) -> Unit
) {
    val media = image.image ?: run { image.close(); return }
    val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.let { onResult(it.rawValue!!) }
        }
        .addOnCompleteListener { image.close() }
}
