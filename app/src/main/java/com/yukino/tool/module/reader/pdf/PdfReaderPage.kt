@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yukino.tool.module.reader.pdf

import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
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

private const val EDGE_DEADZONE_DP = 24
private const val FLICK_VELOCITY_DP_S = 900f
// 页位图缓存: 按字节限额的 LRU(ARGB_8888 4B/px, 一屏宽约 1080×1500×4 ≈ 6.5MB, 容 ~14 页)
private const val BITMAP_CACHE_BYTES = 96 * 1024 * 1024

// PDF 阅读文档: pfd + renderer 生命周期绑定屏幕;渲染串行化(PdfRenderer 非线程安全)
class PdfDoc(path: String) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()

    val pageCount: Int get() = renderer.pageCount

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

// PDF 阅读页: 与文本流 ReaderScreen 平行的固定版式路径。
// 复用: 翻页手势语义/菜单浮层/目录弹层/跳页弹窗/设置弹层;不复用: 分页引擎/选区/脚注/混合渲染。
// 位置状态只有 0 基页码 pageIndex;进度锚点 = 页码(存 progress.globalCharOffset)。
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
    // 位置: 0 基页码,初始值取持久化进度(PDF 语义: progress.globalCharOffset 存页码)
    var pageIndex by remember(book.id) {
        mutableStateOf(if (book.ready) book.progress.globalCharOffset.toInt().coerceAtLeast(0) else 0)
    }
    var currentBmp by remember(book.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var viewport by remember { mutableStateOf<IntSize?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showJumpPage by remember { mutableStateOf(false) }
    val pageViewRef = remember { mutableStateOf<PdfPageView?>(null) }

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

    // 渲染当前页 + 预取邻页(±2)
    LaunchedEffect(doc, pageIndex, bmpWidth) {
        if (doc == null || bmpWidth == 0) return@LaunchedEffect
        val idx = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        currentBmp = getOrRender(idx)
        for (off in intArrayOf(1, -1, 2, -2)) {
            val i = idx + off
            if (i !in 0 until pageCount) continue
            launch(Dispatchers.Default) { runCatching { getOrRender(i) } }
        }
    }

    // 进度上报(内存态,上层防抖落盘): 页码即锚点,percent 仅展示
    LaunchedEffect(pageIndex, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        val idx = pageIndex.coerceIn(0, pageCount - 1)
        onProgress(idx.toLong(), (idx + 1).toDouble() / pageCount)
    }

    // 目录条目 → 页码(ChapterIndex.startChar 存 0 基页码)
    fun chapterIdxForPage(page: Int): Int {
        var cur = 0
        for (i in book.chapters.indices) {
            if (book.chapters[i].startChar <= page) cur = i else break
        }
        return cur
    }

    fun seekToPage(target: Int) {
        if (pageCount <= 0) return
        pageIndex = target.coerceIn(0, pageCount - 1)
    }

    fun seekToPercent(p: Float) {
        if (pageCount <= 0) return
        seekToPage(((p.coerceIn(0f, 1f) * pageCount).roundToInt() - 1).coerceAtLeast(0))
    }

    BackHandler { onBack() }

    val bgColor = Color(settings.effectiveBg)
    val fgColor = Color(settings.effectiveFg)
    val secondary = fgColor.copy(alpha = 0.55f)
    val menuBg = lerp(bgColor, Color.Black, 0.06f)
    val percent = if (pageCount > 0) (pageIndex.coerceIn(0, pageCount - 1) + 1).toDouble() / pageCount else 0.0

    val sysPad = WindowInsets.systemBars.asPaddingValues()
    val topInsetPx = with(density) { sysPad.calculateTopPadding().toPx() }.toInt()
    val bottomInsetPx = with(density) { sysPad.calculateBottomPadding().toPx() }.toInt()

    // 手势闭包防过期
    val liveDoc by rememberUpdatedState(doc)
    val livePageCount by rememberUpdatedState(pageCount)
    val livePageIndex by rememberUpdatedState(pageIndex)
    val liveCurrentBmp by rememberUpdatedState(currentBmp)

    // 拖拽翻页会话(简化版: 邻页位图就绪才可跟手;未就绪时轻扫直达)
    class Session(val dir: Int, val target: Int, val cur: android.graphics.Bitmap, val neighbor: android.graphics.Bitmap)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .onSizeChanged {
                viewport = IntSize(it.width, (it.height - topInsetPx - bottomInsetPx).coerceAtLeast(0))
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { menuVisible = !menuVisible })
            }
            .pointerInput(menuVisible, pageCount, bmpWidth) {
                if (menuVisible || pageCount <= 0) return@pointerInput
                var startX = 0f
                var totalDrag = 0f
                var velocityPxPerSec = 0f
                var lastEventTimeMs = 0L
                var session: Session? = null

                suspend fun resolve(dir: Int): Session? {
                    val d = liveDoc ?: return null
                    val n = livePageCount
                    if (n <= 0) return null
                    val idx = livePageIndex.coerceIn(0, n - 1)
                    val target = if (dir > 0) idx + 1 else idx - 1
                    if (target !in 0 until n) return null
                    val cur = liveCurrentBmp ?: return null
                    val neighbor = getOrRender(target) ?: return null   // 未就绪则页面静止,预取后就绪后重试
                    return Session(dir, target, cur, neighbor)
                }

                var resolving = false
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x
                        totalDrag = 0f
                        velocityPxPerSec = 0f
                        lastEventTimeMs = 0L
                        session = null
                    },
                    onHorizontalDrag = { change, amount ->
                        totalDrag += amount
                        val dt = change.uptimeMillis - lastEventTimeMs
                        if (lastEventTimeMs != 0L && dt > 0) {
                            val instant = amount / dt * 1000f
                            velocityPxPerSec =
                                if (velocityPxPerSec == 0f || abs(instant) > abs(velocityPxPerSec)) instant
                                else velocityPxPerSec
                        }
                        lastEventTimeMs = change.uptimeMillis
                        if (totalDrag == 0f) return@detectHorizontalDragGestures
                        val v = pageViewRef.value ?: return@detectHorizontalDragGestures
                        val dir = if (totalDrag < 0) 1 else -1
                        val s = session
                        if (s == null || s.dir != dir) {
                            // 邻页渲染是挂起操作: 异步解析,就绪前页面静止(预取命中则下一事件即跟手)
                            if (!resolving) {
                                resolving = true
                                scope.launch { session = resolve(dir); resolving = false }
                            }
                        }
                        session?.let { v.showDrag(it.cur, it.neighbor, it.dir, totalDrag) }
                    },
                    onDragEnd = {
                        val distanceFlip = abs(totalDrag) > size.width / 4f
                        val flickFlip = abs(velocityPxPerSec) > FLICK_VELOCITY_DP_S * density.density
                        val shouldFlip = distanceFlip || flickFlip
                        val v = pageViewRef.value
                        val s = session
                        if (s != null) {
                            if (shouldFlip) pageIndex = s.target
                            v?.animateDragEnd(shouldFlip) {}
                        } else {
                            // 邻页未就绪: 退化直切(死区让位系统返回的同向手势)
                            val edgePx = with(density) { EDGE_DEADZONE_DP.dp.toPx() }
                            val fromLeftBand = startX < edgePx
                            val fromRightBand = startX > size.width - edgePx
                            val sameDirAsBack =
                                (fromLeftBand && totalDrag > 0f) || (fromRightBand && totalDrag < 0f)
                            if (!sameDirAsBack && shouldFlip) {
                                val n = livePageCount
                                val idx = livePageIndex.coerceIn(0, (n - 1).coerceAtLeast(0))
                                if (totalDrag < 0 && idx < n - 1) pageIndex = idx + 1
                                if (totalDrag > 0 && idx > 0) pageIndex = idx - 1
                            }
                        }
                        session = null
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx -> PdfPageView(ctx).also { pageViewRef.value = it } },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.setContentInsets(topInsetPx, bottomInsetPx)
                v.setColors(bgColor.toArgb())
                currentBmp?.let { v.setPage(it) }
            }
        )

        // loading 延迟显示(与文本流同策略)
        val contentReady = doc != null && currentBmp != null
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
                    text = "${pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) + 1}/$pageCount",
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
                        text = "第 ${pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) + 1} 页 · 共 $pageCount 页",
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
            currentChapter = chapterIdxForPage(pageIndex),
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
        var input by remember(showJumpPage) { mutableStateOf((pageIndex + 1).toString()) }
        val target = input.trim().toIntOrNull()
        AlertDialog(
            onDismissRequest = { showJumpPage = false },
            title = { Text("跳页") },
            text = {
                Column {
                    Text(
                        "当前第 ${pageIndex + 1} 页 / 共 $pageCount 页",
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
