@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.yukino.tool.module.reader.pdf

import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.WindowCompat
import com.yukino.tool.module.reader.BookInitException
import com.yukino.tool.module.reader.ReaderSettingsSheet
import com.yukino.tool.module.reader.ReaderTocSheet
import com.yukino.tool.module.reader.common.ReaderBook
import com.yukino.tool.module.reader.common.ReaderSettings
import com.yukino.tool.util.findActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val FLICK_VELOCITY_DP_S = 900f
// 页位图缓存: 按字节限额的 LRU(ARGB_8888 4B/px, 一屏宽约 1080×1500×4 ≈ 6.5MB, 容 ~14 页)
private const val BITMAP_CACHE_BYTES = 96 * 1024 * 1024

// PDF 阅读文档: pfd + renderer 生命周期绑定屏幕;渲染串行化(PdfRenderer 非线程安全)
class PdfDoc(path: String) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()

    val pageCount: Int get() = renderer.pageCount

    // 页宽高比(高/宽,以第 1 页为准;滚动列表按此定条目高度)
    val pageAspect: Float = run {
        val page = renderer.openPage(0)
        try {
            (page.height.toFloat() / page.width).coerceAtLeast(0.1f)
        } finally {
            page.close()
        }
    }

    suspend fun render(index: Int, bitmapWidth: Int): android.graphics.Bitmap =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                PdfImporter.renderPage(renderer, index, bitmapWidth)
            }
        }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

// PDF 阅读页(连续滚动模式): 整本一个竖向滚动列表,页面位图按可视窗口惰性渲染;
// 无翻页手势——上下滚动即翻页,页码/进度按首个可视页实时落账;双击某页放大/还原。
// 复用: 菜单浮层/目录弹层/跳页弹窗/设置弹层;进度锚点 = 页码(存 progress.globalCharOffset)。
@Composable
fun PdfScreen(
    book: ReaderBook,
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onProgress: (page: Long, percent: Double) -> Unit,
    onInitComplete: (ReaderBook) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var doc by remember(book.id) { mutableStateOf<PdfDoc?>(null) }
    var initStage by remember(book.id) { mutableStateOf<String?>(null) }
    var loadError by remember(book.id) { mutableStateOf<String?>(null) }
    var viewport by remember { mutableStateOf<IntSize?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showJumpPage by remember { mutableStateOf(false) }

    // 屏幕常亮
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 系统栏透明, 图标亮度跟主题(与文本流阅读页同约定)
    LaunchedEffect(settings) {
        val window = runCatching { context.findActivity().window }.getOrNull() ?: return@LaunchedEffect
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val lightBars = Color(settings.effectiveBg).luminance() > 0.5f
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    // 懒初始化(登记态: 转存/目录/封面) + 打开渲染器
    LaunchedEffect(book.id) {
        loadError = null
        try {
            val refreshed = PdfImporter.ensureReady(context, book) { initStage = it }
            if (refreshed != book) onInitComplete(refreshed)
            doc = PdfDoc(refreshed.cachePath)
        } catch (e: BookInitException) {
            loadError = e.message ?: "打开失败"
        } catch (e: Exception) {
            loadError = "打开失败: ${e.message ?: "未知错误"}"
        }
        initStage = null
    }

    DisposableEffect(book.id) {
        onDispose { doc?.close() }
    }

    val pageCount = doc?.pageCount ?: 0

    // 滚动位置: 首个可视页(0 基)即当前页;初始落持久化进度页
    val savedPage = if (book.ready) book.progress.globalCharOffset.toInt().coerceAtLeast(0) else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = savedPage)
    val currentPage by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

    // 页位图缓存(viewport 宽变化即整体失效;渲染串行,上限 BITMAP_CACHE_BYTES)
    val bmpWidth = viewport?.width?.coerceIn(1, 2200) ?: 0
    val bmpCache = remember(book.id, bmpWidth) {
        java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Bitmap>()
    }
    suspend fun getOrRender(i: Int): android.graphics.Bitmap? {
        val d = doc ?: return null
        if (i !in 0 until d.pageCount) return null
        bmpCache[i]?.let { return it }
        val bmp = d.render(i, bmpWidth)
        // 简易 LRU: 超限按插入序淘汰(ConcurrentHashMap 无序,足够近似)
        while (bmpCache.size > 1 &&
            bmpCache.values.sumOf { it.byteCount } > BITMAP_CACHE_BYTES
        ) {
            bmpCache.keys.firstOrNull()?.let { bmpCache.remove(it) } ?: break
        }
        bmpCache[i] = bmp
        return bmp
    }

    // 预渲染可视页与其后 2 页/前 1 页(滚动方向向下的概率更高)
    LaunchedEffect(doc, currentPage, bmpWidth) {
        if (doc == null || bmpWidth == 0) return@LaunchedEffect
        for (off in intArrayOf(0, 1, 2, -1)) {
            val i = currentPage + off
            if (i !in 0 until pageCount) continue
            launch(Dispatchers.Default) { runCatching { getOrRender(i) } }
        }
    }

    // 进度上报(内存态,上层防抖落盘): 首个可视页即当前页
    LaunchedEffect(currentPage, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        val idx = currentPage.coerceIn(0, pageCount - 1)
        onProgress(idx.toLong(), (idx + 1).toDouble() / pageCount)
    }

    fun seekToPage(target: Int) {
        if (pageCount <= 0) return
        scope.launch { listState.scrollToItem(target.coerceIn(0, pageCount - 1)) }
    }

    fun seekToPercent(p: Float) {
        if (pageCount <= 0) return
        seekToPage(((p.coerceIn(0f, 1f) * pageCount).roundToInt() - 1).coerceAtLeast(0))
    }

    // 目录条目 → 页码(ChapterIndex.startChar 存 0 基页码)
    fun chapterIdxForPage(page: Int): Int {
        var cur = 0
        for (i in book.chapters.indices) {
            if (book.chapters[i].startChar <= page) cur = i else break
        }
        return cur
    }

    BackHandler { onBack() }

    val bgColor = Color(settings.effectiveBg)
    val fgColor = Color(settings.effectiveFg)
    val secondary = fgColor.copy(alpha = 0.55f)
    val menuBg = lerp(bgColor, Color.Black, 0.06f)
    val percent = if (pageCount > 0) (currentPage.coerceIn(0, pageCount - 1) + 1).toDouble() / pageCount else 0.0

    val sysPad = WindowInsets.systemBars.asPaddingValues()
    val topInsetPx = with(density) { sysPad.calculateTopPadding().toPx() }.toInt()
    val bottomInsetPx = with(density) { sysPad.calculateBottomPadding().toPx() }.toInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .onSizeChanged {
                viewport = IntSize(it.width, (it.height - topInsetPx - bottomInsetPx).coerceAtLeast(0))
            }
    ) {
        // 连续滚动页列表: 页与页之间 2dp 缝,上下避让系统栏
        if (doc != null && pageCount > 0 && bmpWidth > 0) {
            val pageAspect = doc!!.pageAspect
            LazyColumn(
                state = listState,
                // 呼菜单的单击由页面条目自己处理(onSingleTap): 外层 detectTapGestures
                // 会消费 down, 把 LazyColumn 的滚动手势掐死
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = with(density) { topInsetPx.toDp() },
                    bottom = with(density) { bottomInsetPx.toDp() }
                )
            ) {
                items(count = pageCount, key = { it }) { i ->
                    PdfPageItem(
                        index = i,
                        width = bmpWidth,
                        aspect = pageAspect,
                        bg = bgColor.toArgb(),
                        fg = fgColor.toArgb(),
                        getOrRender = { getOrRender(i) },
                        cache = bmpCache,
                        onDoubleTapZoom = { /* 预留 */ },
                        onSingleTap = { menuVisible = !menuVisible }
                    )
                }
            }
        }

        // loading 延迟显示(与文本流同策略)
        val contentReady = doc != null && pageCount > 0
        var showLoading by remember(book.id) { mutableStateOf(false) }
        LaunchedEffect(contentReady) {
            if (contentReady) showLoading = false
            else {
                delay(350)
                showLoading = true
            }
        }
        if (!contentReady && showLoading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                if (loadError != null) {
                    Text(
                        loadError + "，请返回书架删除后重新导入",
                        color = secondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    CircularProgressIndicator(color = fgColor)
                    initStage?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 菜单浮层: 顶栏(与文本流同款)
        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(menuBg)
                    .statusBarsPadding()
                    .systemGestureExclusion()
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = fgColor)
                }
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = fgColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) + 1}/$pageCount",
                    style = MaterialTheme.typography.labelLarge,
                    color = secondary,
                    modifier = Modifier.padding(end = 12.dp)
                )
                IconButton(onClick = { showToc = true }) {
                    Icon(Icons.AutoMirrored.Rounded.List, "目录", tint = fgColor)
                }
            }
        }

        // 菜单浮层: 底栏(跳页 + 进度条 + 设置)
        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(menuBg)
                    .navigationBarsPadding()
                    .systemGestureExclusion()
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var sliderValue by remember(menuVisible) { mutableStateOf<Float?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showJumpPage = true }) {
                        Icon(Icons.Rounded.Input, "跳页", tint = fgColor)
                    }
                    Slider(
                        modifier = Modifier.weight(1f),
                        value = (sliderValue ?: percent.toFloat()).coerceIn(0f, 1f),
                        colors = SliderDefaults.colors(
                            thumbColor = fgColor,
                            activeTrackColor = fgColor,
                            inactiveTrackColor = fgColor.copy(alpha = 0.22f)
                        ),
                        track = { _ ->
                            val f = (sliderValue ?: percent.toFloat()).toFloat().coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(fgColor.copy(alpha = 0.22f))
                            ) {
                                if (f > 0f) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(fgColor)
                                    )
                                }
                            }
                        },
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            sliderValue?.let { seekToPercent(it) }
                            sliderValue = null
                        }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "${(percent * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = secondary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "第 ${currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) + 1} 页 · 共 $pageCount 页",
                        style = MaterialTheme.typography.labelLarge,
                        color = secondary
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, "设置", tint = fgColor)
                    }
                }
            }
        }
    }

    if (showToc) {
        ReaderTocSheet(
            book = book,
            currentChapter = chapterIdxForPage(currentPage),
            onChapterClick = { idx ->
                showToc = false
                if (idx in book.chapters.indices) {
                    seekToPage(book.chapters[idx].startChar.toInt())
                }
            },
            onDismiss = { showToc = false }
        )
    }

    if (showJumpPage) {
        var input by remember(showJumpPage) { mutableStateOf((currentPage + 1).toString()) }
        val target = input.trim().toIntOrNull()
        AlertDialog(
            onDismissRequest = { showJumpPage = false },
            title = { Text("跳页") },
            text = {
                Column {
                    Text(
                        "当前第 ${currentPage + 1} 页 / 共 $pageCount 页",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { s -> input = s.filter { it.isDigit() }.take(7) },
                        singleLine = true,
                        isError = target == null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(onGo = {
                            if (target != null && pageCount > 0) {
                                seekToPage(target - 1)
                                showJumpPage = false
                            }
                        })
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = target != null && pageCount > 0,
                    onClick = {
                        seekToPage((target ?: 1) - 1)
                        showJumpPage = false
                    }
                ) { Text("跳转") }
            },
            dismissButton = {
                TextButton(onClick = { showJumpPage = false }) { Text("取消") }
            }
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            onChange = onSettingsChange,
            onDismiss = { showSettings = false }
        )
    }
}

// 滚动列表单页条目: 位图惰性渲染(条目进入组合即触发),双击放大/还原(以点击点为焦点),
// 单击冒泡给列表层呼出菜单。渲染中显示页码占位
@Composable
private fun PdfPageItem(
    index: Int,
    width: Int,
    aspect: Float,
    bg: Int,
    fg: Int,
    getOrRender: suspend (Int) -> android.graphics.Bitmap?,
    cache: java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Bitmap>,
    onDoubleTapZoom: () -> Unit,
    onSingleTap: () -> Unit
) {
    var bmp by remember(index, width) { mutableStateOf(cache[index]) }
    LaunchedEffect(index, width) {
        if (bmp == null) bmp = getOrRender(index)
    }
    var scale by remember(index) { mutableStateOf(1f) }
    var origin by remember(index) { mutableStateOf(androidx.compose.ui.geometry.Offset(0.5f, 0.5f)) }
    var pan by remember(index) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var itemSize by remember(index) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val zoomed = scale > 1.05f
    val scope = rememberCoroutineScope()

    // 平移钳制: 放大后页面可视矩形不许拖出条目框(transformOrigin 中心缩放公式)
    fun clampPan(): androidx.compose.ui.geometry.Offset {
        val s = scale
        if (s <= 1f) return androidx.compose.ui.geometry.Offset.Zero
        val w = itemSize.width.coerceAtLeast(1).toFloat()
        val h = itemSize.height.coerceAtLeast(1).toFloat()
        val ox = origin.x * w
        val oy = origin.y * h
        val minX = -(w - ox) * (s - 1)
        val maxX = ox * (s - 1)
        val minY = -(h - oy) * (s - 1)
        val maxY = oy * (s - 1)
        return androidx.compose.ui.geometry.Offset(pan.x.coerceIn(minX, maxX), pan.y.coerceIn(minY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (aspect > 0f) 1f / aspect else 1.4f)
            .background(Color(bg))
            .clipToBounds()
            .onSizeChanged { itemSize = it }
            .then(
                if (zoomed) Modifier.pointerInput(Unit) {
                    // 放大态: 拖动平移看细节(消费拖动,列表不滚动);范围钳在条目框内
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            pan = androidx.compose.ui.geometry.Offset(
                                pan.x + amount.x, pan.y + amount.y
                            )
                            pan = clampPan()
                        }
                    )
                } else Modifier
            )
            .pointerInput(index) {
                // 自实现双击: 第一击不立即呼菜单(延迟 300ms, 期间来第二击=双击缩放),
                // 避免"第一击开菜单干扰第二击"——zoom 稳定性优先
                var tapJob: kotlinx.coroutines.Job? = null
                var lastT = 0L
                var lastX = 0f
                var lastY = 0f
                detectTapGestures(
                    onTap = { o ->
                        val now = android.os.SystemClock.uptimeMillis()
                        val dbl = now - lastT < 500 &&
                            abs(o.x - lastX) < size.width / 3f &&
                            abs(o.y - lastY) < size.height / 3f
                        tapJob?.cancel()
                        if (dbl) {
                            lastT = 0
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val target = if (scale > 1.5f) 1f else 2.5f
                            val newOrigin = androidx.compose.ui.geometry.Offset(o.x / w, o.y / h)
                            val o0 = origin
                            val s0 = scale
                            scope.launch {
                                animate(0f, 1f, animationSpec = tween(200)) { v, _ ->
                                    scale = s0 + (target - s0) * v
                                    origin = androidx.compose.ui.geometry.Offset(
                                        o0.x + (newOrigin.x - o0.x) * v,
                                        o0.y + (newOrigin.y - o0.y) * v
                                    )
                                }
                            }
                        } else {
                            lastT = now
                            lastX = o.x
                            lastY = o.y
                            tapJob = scope.launch {
                                delay(300)
                                onSingleTap()
                            }
                        }
                    }
                )
            }
    ) {
        val b = bmp
        if (b != null) {
            androidx.compose.foundation.Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "第 ${index + 1} 页",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = clampPan()
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(origin.x, origin.y)
                        translationX = p.x
                        translationY = p.y
                    }
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                CircularProgressIndicator(color = Color(fg), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(fg)
                )
            }
        }
    }
}
