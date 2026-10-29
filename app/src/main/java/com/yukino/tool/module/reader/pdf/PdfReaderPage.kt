@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.yukino.tool.module.reader.pdf

import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.VectorConverter
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
import com.yukino.tool.module.reader.BookRegistrar
import com.yukino.tool.module.reader.ReaderSettingsSheet
import com.yukino.tool.module.reader.ReaderStore
import com.yukino.tool.module.reader.ReauthCheck
import com.yukino.tool.module.reader.ReauthMismatchDialog
import com.yukino.tool.module.reader.SourcePathOverlay
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
fun PdfPage(
    book: ReaderBook,
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onProgress: (page: Long, percent: Double, scrollOffset: Int) -> Unit,
    onInitComplete: (ReaderBook) -> Unit,
    pickFile: suspend () -> Uri?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var doc by remember(book.id) { mutableStateOf<PdfDoc?>(null) }
    var initStage by remember(book.id) { mutableStateOf<String?>(null) }
    var loadError by remember(book.id) { mutableStateOf<String?>(null) }
    // 源文件 SAF 授权丢失: 展示"重新授权"入口而非删除重导提示
    var authLost by remember(book.id) { mutableStateOf(false) }
    // 重选文件与原书不一致: 弹详情对话框(差异清单/强制授权)
    var reauthMismatch by remember(book.id) { mutableStateOf<ReauthCheck?>(null) }
    // 重载触发: 重新授权后自增。不能只 key sourceUri——重选同一文件时 uri 串不变
    var reloadNonce by remember(book.id) { mutableStateOf(0) }
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
    // key 含 reloadNonce: 重新授权换源后同 id 重载(进度不丢)
    LaunchedEffect(book.id, reloadNonce) {
        loadError = null
        authLost = false
        try {
            val refreshed = PdfImporter.ensureReady(context, book) { initStage = it }
            if (refreshed != book) onInitComplete(refreshed)
            doc?.close()
            doc = PdfDoc(refreshed.cachePath)
        } catch (e: SecurityException) {
            // copySource 读源 uri 无授权: 授权被系统收回(卸载重装/权限自动重置等)
            loadError = "源文件访问授权已失效"
            authLost = true
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

    // 滚动位置: 首个可视页(0 基)即当前页;初始落持久化进度页+页内偏移
    val savedPage = if (book.ready) book.progress.globalCharOffset.toInt().coerceAtLeast(0) else 0
    // 偏移存档只在与进度页码同源时可信(跳页/重分页后页码变了, 旧偏移不能跨页用)
    val savedOffset = if (book.ready) book.progress.scrollOffset.coerceAtLeast(0) else 0
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedPage,
        initialFirstVisibleItemScrollOffset = savedOffset
    )
    // 双击放大的页: 缩放/平移由列表上方的全屏 overlay 承接,列表禁滚,还原后原地恢复
    var zoomedIndex by remember(book.id) { mutableStateOf<Int?>(null) }
    // 呼出放大时的双击焦点(页面内归一化坐标),overlay 以它为放大中心
    var zoomFocal by remember(book.id) { mutableStateOf(androidx.compose.ui.geometry.Offset(0.5f, 0.5f)) }
    // 放大前的列表滚动位置: 放大态条目跳变会重置 LazyList 的 scrollOffset,还原后显式滚回
    var savedScroll by remember(book.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    // 进度退出补报须先于 doc 释放注册: onDispose 按注册顺序执行, renderer 关闭后 pageCount=0 会跳过上报
    DisposableEffect(Unit) {
        onDispose {
            // 实时读 doc: pageCount 是组合期局部 val, 闭包捕获的是首次组合(doc 未载)的 0
            val pc = doc?.pageCount ?: 0
            if (pc > 0) {
                val idx = listState.firstVisibleItemIndex.coerceIn(0, pc - 1)
                onProgress(idx.toLong(), (idx + 1).toDouble() / pc, listState.firstVisibleItemScrollOffset)
            }
        }
    }

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

    // 进度上报(内存态,上层防抖落盘): 首个可视页+页内偏移。页码变化时上报;
    // 页内滚动不改页码, 退出组合时补报一次, 保证重开恢复到退出时的精确位置
    LaunchedEffect(currentPage, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        val idx = currentPage.coerceIn(0, pageCount - 1)
        onProgress(idx.toLong(), (idx + 1).toDouble() / pageCount, listState.firstVisibleItemScrollOffset)
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
    // 放大态返回=先还原放大,再按才退书(声明在后,组合序更先响应)
    if (zoomedIndex != null) {
        BackHandler { zoomedIndex = null }
    }
    // 还原后精确滚回放大前位置: 须等 zoomedIndex=null 的重组应用、列表条目恢复后才可滚,
    // 在 onDismiss 里立即滚会因"列表仍只有放大条目、无可滚空间"而被吞
    LaunchedEffect(zoomedIndex) {
        if (zoomedIndex == null) {
            savedScroll?.let { (idx, off) ->
                listState.scrollToItem(idx)
                listState.dispatchRawDelta(off.toFloat())
            }
            savedScroll = null
        }
    }

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
                userScrollEnabled = zoomedIndex == null,   // 放大态独占视口,禁列表滚动
                contentPadding = PaddingValues(
                    top = with(density) { topInsetPx.toDp() },
                    bottom = with(density) { bottomInsetPx.toDp() }
                )
            ) {
                items(count = pageCount, key = { it }) { i ->
                    if (zoomedIndex != null && zoomedIndex != i) return@items   // 放大时列表只保留该页(被 overlay 盖住)
                    PdfPageItem(
                        index = i,
                        width = bmpWidth,
                        aspect = pageAspect,
                        bg = bgColor.toArgb(),
                        fg = fgColor.toArgb(),
                        getOrRender = { getOrRender(i) },
                        cache = bmpCache,
                        onZoomRequest = { focal ->
                            zoomFocal = focal
                            savedScroll = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                            zoomedIndex = i
                        },
                        onSingleTap = { menuVisible = !menuVisible }
                    )
                }
            }

            // 全屏放大层: 双击放大的页提到列表上方独立缩放/平移(放大内容铺满全屏),
            // 相邻页被盖住,还原后摘掉 overlay 并精确滚回放大前位置
            zoomedIndex?.let { zi ->
                PdfZoomOverlay(
                    index = zi,
                    width = bmpWidth,
                    aspect = pageAspect,
                    bg = bgColor.toArgb(),
                    fg = fgColor.toArgb(),
                    initialFocal = zoomFocal,
                    getOrRender = { getOrRender(zi) },
                    cache = bmpCache,
                    onDismiss = {
                        zoomedIndex = null   // 滚动恢复由 LaunchedEffect(zoomedIndex) 在条目恢复后执行
                    },
                    onSingleTap = { menuVisible = !menuVisible }
                )
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
                    if (authLost) {
                        Text(
                            "源文件访问授权已失效，重新授权后继续阅读（进度保留）",
                            color = secondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                            scope.launch {
                                // 悬浮条需"显示在其他应用上层"权限: 未授予先跳系统设置, 开启后返回重试
                                if (!android.provider.Settings.canDrawOverlays(context)) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                    Toast.makeText(
                                        context, "请先允许\"显示在其他应用上层\"权限，开启后返回重试",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@launch
                                }
                                // 选择器期间悬浮展示原文件绝对路径, 指引在选择器中定位同一文件
                                SourcePathOverlay.show(
                                    context,
                                    BookRegistrar.sourceAbsolutePath(Uri.parse(book.sourceUri))
                                )
                                val picked = try {
                                    pickFile()
                                } finally {
                                    SourcePathOverlay.hide(context)
                                } ?: return@launch
                                val check = withContext(Dispatchers.IO) {
                                    BookRegistrar.reauthorizeCheck(context, book, picked)
                                }
                                val updated = check.matched
                                if (updated != null) {
                                    withContext(Dispatchers.IO) { ReaderStore.upsertBook(context, updated) }
                                    onInitComplete(updated)
                                    reloadNonce++   // 触发同 id 重载
                                } else {
                                    reauthMismatch = check   // 差异详情弹窗, 由用户决定强制/取消
                                }
                            }
                        }) { Text("重新授权") }
                    } else {
                        Text(
                            loadError + "，请返回书架删除后重新导入",
                            color = secondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    CircularProgressIndicator(color = fgColor)
                    initStage?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 重新授权不一致详情: 差异清单 + 类型门禁的强制授权
        reauthMismatch?.let { chk ->
            ReauthMismatchDialog(
                check = chk,
                onForce = {
                    reauthMismatch = null
                    scope.launch {
                        withContext(Dispatchers.IO) { ReaderStore.upsertBook(context, chk.forced) }
                        onInitComplete(chk.forced)
                        reloadNonce++   // 忽略差异强换源, 同 id 重载
                    }
                },
                onDismiss = { reauthMismatch = null }
            )
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

// 滚动列表单页条目: 位图惰性渲染(条目进入组合即触发)。双击上报页面内归一化焦点,
// 由列表上方的全屏放大层承接缩放;单击冒泡给列表层呼出菜单。渲染中显示页码占位
@Composable
private fun PdfPageItem(
    index: Int,
    width: Int,
    aspect: Float,
    bg: Int,
    fg: Int,
    getOrRender: suspend (Int) -> android.graphics.Bitmap?,
    cache: java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Bitmap>,
    onZoomRequest: (androidx.compose.ui.geometry.Offset) -> Unit,
    onSingleTap: () -> Unit
) {
    var bmp by remember(index, width) { mutableStateOf(cache[index]) }
    LaunchedEffect(index, width) {
        if (bmp == null) bmp = getOrRender(index)
    }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (aspect > 0f) 1f / aspect else 1.4f)
            .background(Color(bg))
            .pointerInput(index) {
                // 自实现双击: 第一击不立即呼菜单(延迟 300ms, 期间来第二击=双击呼出放大层),
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
                            onZoomRequest(
                                androidx.compose.ui.geometry.Offset(
                                    o.x / size.width.coerceAtLeast(1),
                                    o.y / size.height.coerceAtLeast(1)
                                )
                            )
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
                modifier = Modifier.fillMaxSize()
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

// 全屏放大层: 承接双击放大的缩放/平移/还原。放大内容裁剪并铺满整个屏幕(沉浸,无上下边带),
// 平移钳制要求放大后的页面矩形始终覆盖整屏;双击还原后摘层,由调用方滚回放大前位置
@Composable
private fun PdfZoomOverlay(
    index: Int,
    width: Int,
    aspect: Float,
    bg: Int,
    fg: Int,
    initialFocal: androidx.compose.ui.geometry.Offset,
    getOrRender: suspend (Int) -> android.graphics.Bitmap?,
    cache: java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Bitmap>,
    onDismiss: () -> Unit,
    onSingleTap: () -> Unit
) {
    var bmp by remember(index, width) { mutableStateOf(cache[index]) }
    LaunchedEffect(index, width) {
        if (bmp == null) bmp = getOrRender(index)
    }
    var scale by remember { mutableStateOf(1f) }
    var origin by remember { mutableStateOf(initialFocal) }
    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var layerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var flingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    // 页面位图真实比例(高/宽): 混比例书的正文页与封面(整书统一 pageAspect)比例不同,
    // 放大层若按统一比例布局,位图 Fit 会在框内左右留白(透出背景),拖到极限必露背景——
    // 按位图真实比例布局使框与位图重合;位图未就绪时退回统一比例
    val effAspect = bmp?.let { it.height.toFloat() / it.width.coerceAtLeast(1) } ?: aspect

    // 页面图实际布局矩形: 与 aspectRatio 布局一致(先按层宽适配, 页高超层则按层高适配), 双向居中。
    // 长条页(页高/页宽 > 层高/层宽)按高适配后页宽小于层宽——钳制与焦点换算必须基于此矩形,
    // 否则拖到极限页面不贴边、左右露背景
    fun pageLayout(w: Float, h: Float): androidx.compose.ui.geometry.Rect {
        var pw = w
        var ph = pw * effAspect
        if (ph > h) {
            ph = h
            pw = if (effAspect > 0f) h / effAspect else w
        }
        return androidx.compose.ui.geometry.Rect(
            androidx.compose.ui.geometry.Offset((w - pw) / 2f, (h - ph) / 2f),
            androidx.compose.ui.geometry.Size(pw, ph)
        )
    }

    // 平移钳制: 放大后的页面矩形须覆盖整个层。页面图上下/左右边缘都可能不在层边缘,
    // 均须用含页面图矩形偏移的完整公式; 放大后仍盖不住层的维度(极扁/极长页)该轴固定居中
    fun clampPan(p: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset {
        val s = scale
        if (s <= 1f) return androidx.compose.ui.geometry.Offset.Zero
        val w = layerSize.width.coerceAtLeast(1).toFloat()
        val h = layerSize.height.coerceAtLeast(1).toFloat()
        val r = pageLayout(w, h)
        val ox = r.left + origin.x * r.width
        val oy = r.top + origin.y * r.height
        val minX = w - ox * (1 - s) - r.right * s
        val maxX = ox * (s - 1) - r.left * s
        val minY = h - oy * (1 - s) - r.bottom * s
        val maxY = oy * (s - 1) - r.top * s
        return androidx.compose.ui.geometry.Offset(
            if (maxX < minX) 0f else p.x.coerceIn(minX, maxX),
            if (maxY < minY) 0f else p.y.coerceIn(minY, maxY)
        )
    }

    // 进入放大: 挂载即以双击焦点为中心从 1x 动画到 2.5x(每帧钳制,结束时页面已铺满)
    LaunchedEffect(Unit) {
        animate(1f, 2.5f, animationSpec = tween(200)) { v, _ ->
            scale = v
            pan = clampPan(pan)
        }
    }

    // 双击缩放切换: 2.5x 以点击点为焦点(origin 移到点击处), 还原归位后摘层
    fun toggleZoom(focal: androidx.compose.ui.geometry.Offset) {
        val target = if (scale > 1.5f) 1f else 2.5f
        val o0 = origin
        val s0 = scale
        scope.launch {
            animate(0f, 1f, animationSpec = tween(200)) { v, _ ->
                scale = s0 + (target - s0) * v
                origin = androidx.compose.ui.geometry.Offset(
                    o0.x + (focal.x - o0.x) * v,
                    o0.y + (focal.y - o0.y) * v
                )
                pan = clampPan(pan)
            }
            pan = clampPan(pan)
            if (target <= 1.5f) onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(bg))
            .clipToBounds()
            .onSizeChanged { layerSize = it }
            .pointerInput(Unit) {
                // 放大态: 拖动平移看细节; 松手按手势速度惯性滑行(衰减), 撞到页面边缘停住
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectDragGestures(
                    onDragStart = { flingJob?.cancel() },
                    onDrag = { change, amount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        pan = clampPan(
                            androidx.compose.ui.geometry.Offset(
                                pan.x + amount.x, pan.y + amount.y
                            )
                        )
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        tracker.resetTracking()
                        flingJob = scope.launch {
                            androidx.compose.animation.core.Animatable(
                                pan, androidx.compose.ui.geometry.Offset.VectorConverter
                            ).animateDecay(
                                androidx.compose.ui.geometry.Offset(v.x, v.y),
                                androidx.compose.animation.core.exponentialDecay(frictionMultiplier = 1.5f)
                            ) {
                                pan = clampPan(value)   // this.value = 每帧惯性位置
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                // 自实现双击: 第一击不立即呼菜单(延迟 300ms, 期间来第二击=双击缩放/还原)
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
                            // 焦点换算到页面图坐标(实际布局矩形, 含长条页水平居中偏移)
                            val r = pageLayout(size.width.toFloat(), size.height.toFloat())
                            toggleZoom(
                                androidx.compose.ui.geometry.Offset(
                                    (o.x - r.left) / r.width, (o.y - r.top) / r.height
                                )
                            )
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
            // 页面图 1x 基准矩形(层内垂直居中);放大内容溢出本矩形,由外层全屏裁剪
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .aspectRatio(if (effAspect > 0f) 1f / effAspect else 1.4f)
            ) {
                androidx.compose.foundation.Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "第 ${index + 1} 页",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = clampPan(pan)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(origin.x, origin.y)
                            translationX = p.x
                            translationY = p.y
                        }
                )
            }
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
