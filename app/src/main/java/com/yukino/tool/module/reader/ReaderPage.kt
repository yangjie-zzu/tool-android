@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yukino.tool.module.reader

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.core.view.WindowCompat
import com.yukino.tool.util.findActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EDGE_DEADZONE_DP = 24

// 快速轻扫翻页的速度阈值(dp/s),不满足滑动距离时轻扫仍可翻页——
// 避免靠左/靠右起步的手指行程不足、永远够不到 1/4 屏宽距离的问题
private const val FLICK_VELOCITY_DP_S = 900f

// 拖拽会话: 手势定向时把目标页物化并锁定,拖动全程只更新位移;
// 松手落账 targetIndex。预览与落账是同一 BookPage,结构上不可能不一致
private class DragSession(
    val dir: Int,               // +1 下一页 / -1 上一页
    val targetIndex: Int,       // 目标全局页号
    val current: BookPage,      // 当前页
    val neighbor: BookPage      // 锁定的目标页
)

// 阅读页: 拖拽翻页(右滑上一页/左滑下一页,边缘 24dp 死区留给系统返回)、
// 单击呼出菜单、进度条拖动跳转、目录跳章。
// 书 = 封面+正文+封底的扁平页序列,位置状态只有全局页号 pageIndex;
// 目录未就绪(打开书/改设置整本重算期间)由 loading 遮盖,不存在"部分就绪"状态
@Composable
fun ReaderScreen(
    book: ReaderBook,
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onProgress: (globalCharOffset: Long, percent: Double) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current

    var fullText by remember(book.id) { mutableStateOf<String?>(null) }
    var cacheMissing by remember(book.id) { mutableStateOf(false) }
    // 全书页目录: null=构建中(loading 遮盖)。版式变化整本重建,锚点重定位不漂移
    var specs by remember(book.id) { mutableStateOf<List<PageSpec>?>(null) }
    // specs 对应的版式指纹: 与 typoKey 一致才可信(区别于"旧版式的遗留结果")
    var specsTypoKey by remember(book.id) { mutableStateOf<Int?>(null) }
    // 位置: 全局页号(唯一位置状态)
    var pageIndex by remember(book.id) { mutableStateOf(0) }
    // 当前物化页(拖拽邻居按需物化后存于手势会话,即用即弃)
    var currentBookPage by remember(book.id) { mutableStateOf<BookPage?>(null) }
    var viewport by remember { mutableStateOf<IntSize?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // 跟手拖拽需要手势层直接驱动 View,持有实例引用
    val pageViewRef = remember { mutableStateOf<ReaderPageView?>(null) }

    // 屏幕常亮
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 边到边: 系统栏透明悬浮在阅读背景上;背景亮→深色图标,背景暗→浅色图标
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

    // 加载缓存文本;缺失则用 sourceUri 重新转存
    LaunchedEffect(book.id) {
        val text = withContext(Dispatchers.IO) {
            val f = File(book.cachePath)
            if (f.exists() && f.length() > 0) {
                f.readText()
            } else {
                val refreshed = TxtImporter.ensureCache(context, book)
                File(refreshed.cachePath).takeIf { it.exists() && it.length() > 0 }?.readText()
            }
        }
        cacheMissing = text == null
        fullText = text
    }

    val typo = viewport?.let {
        Typography.resolve(density.density, settings, it.width, it.height)
    }
    // 版式键: 影响断行/颜色的全部字段,变化即整本重建
    val typoKey = typo?.let {
        listOf(
            it.fontPx, it.lineExtraPx, it.paraExtraPx, it.indentPx, it.marginPx,
            it.textWidth, it.textHeight, it.justify, it.fgColor,
            Typography.BREAK_STRATEGY_VERSION   // 断行算法升级时使旧缓存失效
        ).hashCode()
    }

    // 手势闭包防过期: 拖拽中读最新状态
    val liveBook by rememberUpdatedState(book)
    val liveText by rememberUpdatedState(fullText)
    val liveTypo by rememberUpdatedState(typo)
    val liveSpecs by rememberUpdatedState(specs)
    val livePageIndex by rememberUpdatedState(pageIndex)
    val livePage by rememberUpdatedState(currentBookPage)

    // 全书页目录: 文本/视口/版式任一变化 → 后台整本重算,按锚点重定位(打开书时锚点=持久化进度)。
    // 分页结果持久化缓存(ReaderStore.specs): 同一本书版式未变时二次进入直接命中,免整本重排
    LaunchedEffect(fullText, typoKey) {
        val text = fullText ?: return@LaunchedEffect
        val t = typo ?: return@LaunchedEffect
        val key = typoKey ?: return@LaunchedEffect
        if (specs != null && specsTypoKey == key) return@LaunchedEffect   // 已是当前版式,不重排
        // 锚点 = 当前页页首偏移(阅读中改版式不丢位置);无当前页(刚打开书)才用持久化进度
        val anchor = specs?.getOrNull(pageIndex)?.globalCharOffset ?: book.progress.globalCharOffset
        // 缓存命中: 直接用,跳过整本重排
        val cached = withContext(Dispatchers.IO) {
            ReaderStore.loadSpecs(context, book.id, key, book.totalChars)
        }
        if (cached != null) {
            specsTypoKey = key
            specs = cached
            pageIndex = BookPager.locatePage(cached, anchor)
            return@LaunchedEffect
        }
        val result = BookPager.buildSpecs(book, text, t)
        specsTypoKey = key
        specs = result
        pageIndex = BookPager.locatePage(result, anchor)
        withContext(Dispatchers.IO) { ReaderStore.saveSpecs(context, book.id, key, book.totalChars, result) }
    }

    // 物化当前页: 目录/页号/版式就绪 → 后台构建
    LaunchedEffect(specs, pageIndex, typoKey) {
        val sp = specs ?: return@LaunchedEffect
        val t = typo ?: return@LaunchedEffect
        val text = fullText ?: return@LaunchedEffect
        if (sp.isEmpty()) return@LaunchedEffect
        val idx = pageIndex.coerceIn(0, sp.lastIndex)
        currentBookPage = withContext(Dispatchers.Default) {
            BookPager.materialize(book, text, sp[idx], t)
        }
    }

    // 邻页预物化缓存: 翻页落定后后台把前/后页备好,拖拽定向时查表即中,
    // 避免在手势回调(主线程)上构建 StaticLayout 造成起手卡顿。版式/目录变化整表重建
    // ConcurrentHashMap: 邻页在 Default 线程并行物化写入,主线程手势定向时读取
    val neighborCache = remember(book.id, specs, typoKey) {
        java.util.concurrent.ConcurrentHashMap<Int, BookPage>()
    }
    LaunchedEffect(specs, pageIndex, typoKey) {
        val sp = specs ?: return@LaunchedEffect
        val t = typo ?: return@LaunchedEffect
        val text = fullText ?: return@LaunchedEffect
        if (sp.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            for (off in intArrayOf(1, -1)) {
                val i = pageIndex + off
                if (i !in sp.indices || neighborCache.containsKey(i)) continue
                launch(Dispatchers.Default) {
                    neighborCache[i] = BookPager.materialize(book, text, sp[i], t)
                }
            }
        }
    }

    // 进度上报(内存态,ReaderApp 防抖落盘)
    LaunchedEffect(specs, pageIndex) {
        val sp = specs ?: return@LaunchedEffect
        val spec = sp.getOrNull(pageIndex) ?: return@LaunchedEffect
        onProgress(spec.globalCharOffset, BookPager.percentOf(book, spec))
    }

    // 进度条跳转: 全书百分比 → 全局偏移 → 二分定位
    fun seekToPercent(target: Float) {
        val sp = specs ?: return
        if (book.totalChars <= 0 || sp.isEmpty()) return
        val global = (target.coerceIn(0f, 1f) * book.totalChars).roundToInt().toLong()
        pageIndex = BookPager.locatePage(sp, global)
    }

    BackHandler { onBack() }

    val edgePx = with(density) { EDGE_DEADZONE_DP.dp.toPx() }
    val bgColor = Color(settings.effectiveBg)
    val fgColor = Color(settings.effectiveFg)
    val secondary = fgColor.copy(alpha = 0.55f)
    // 菜单浮层配色: 跟随阅读器底色/前景色(加深 6% 做层次),不用主题 surface(浅色主题下是白块)
    val menuBg = lerp(bgColor, Color.Black, 0.06f)
    val percent = currentPercent(book, specs, pageIndex)

    // 系统栏 inset(px): 页面内容(页眉/正文/页脚)在系统栏下方避让,视图本身全屏
    val sysPad = WindowInsets.systemBars.asPaddingValues()
    val topInsetPx = with(density) { sysPad.calculateTopPadding().toPx() }.toInt()
    val bottomInsetPx = with(density) { sysPad.calculateBottomPadding().toPx() }.toInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)                    // 背景延伸到系统栏后面
            .onSizeChanged {
                viewport = IntSize(it.width, (it.height - topInsetPx - bottomInsetPx).coerceAtLeast(0))
            }
            .pointerInput(Unit) {
                detectTapGestures { menuVisible = !menuVisible }
            }
            .pointerInput(menuVisible, typoKey, specs) {
                if (menuVisible) return@pointerInput
                var startX = 0f
                var totalDrag = 0f
                var velocityPxPerSec = 0f
                var lastEventTimeMs = 0L
                var session: DragSession? = null

                // 定向时解析并锁定目标页。返回 null=未就绪或目标不存在(封面/封底越界方向页保持静止)
                fun resolve(dir: Int): DragSession? {
                    val sp = liveSpecs ?: return null
                    val t = liveTypo ?: return null
                    val cur = livePage ?: return null
                    val text = liveText ?: return null
                    if (sp.isEmpty()) return null
                    val idx = livePageIndex.coerceIn(0, sp.lastIndex)
                    val target = if (dir > 0) idx + 1 else idx - 1
                    if (target !in sp.indices) return null
                    // 命中预物化缓存则零成本定向;未命中(理论上仅冷启动首拖)才同步兜底
                    val neighbor = neighborCache.getOrPut(target) {
                        BookPager.materialize(liveBook, text, sp[target], t)
                    }
                    return DragSession(dir, target, cur, neighbor)
                }

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
                        // 峰值保持估算松手速度: 快速轻扫的瞬时峰值才代表手势意图
                        val dt = change.uptimeMillis - lastEventTimeMs
                        if (lastEventTimeMs != 0L && dt > 0) {
                            val instant = amount / dt * 1000f
                            velocityPxPerSec =
                                if (velocityPxPerSec == 0f || abs(instant) > abs(velocityPxPerSec)) {
                                    instant
                                } else {
                                    velocityPxPerSec
                                }
                        }
                        lastEventTimeMs = change.uptimeMillis
                        if (totalDrag == 0f) return@detectHorizontalDragGestures
                        val v = pageViewRef.value ?: return@detectHorizontalDragGestures
                        val dir = if (totalDrag < 0) 1 else -1
                        val s = session
                        if (s == null || s.dir != dir) {
                            // 方向首定/反转: 重新锁定目标(期间无提交,状态未变,安全)
                            session = resolve(dir)
                        }
                        session?.let { v.showDrag(it.current, it.neighbor, it.dir, totalDrag) }
                        // else: 封面/封底越界或目录页未就绪,页面保持静止不响应
                    },
                    onDragEnd = {
                        val distanceFlip = abs(totalDrag) > size.width / 4f
                        val flickFlip = abs(velocityPxPerSec) > FLICK_VELOCITY_DP_S * density.density
                        val shouldFlip = distanceFlip || flickFlip
                        val v = pageViewRef.value
                        val s = session
                        if (s != null) {
                            // 状态先行落账: 页码立即切换,动画纯视觉收尾(被打断不丢页)
                            if (shouldFlip) pageIndex = s.targetIndex
                            v?.animateDragEnd(shouldFlip)
                        } else {
                            // 页目录未就绪的退化直切(loading 门控下几乎不可达)。
                            // 边缘死区只拦与系统返回同向的手势(左缘向右/右缘向左)
                            val fromLeftBand = startX < edgePx
                            val fromRightBand = startX > size.width - edgePx
                            val sameDirAsBack =
                                (fromLeftBand && totalDrag > 0f) || (fromRightBand && totalDrag < 0f)
                            if (!sameDirAsBack && shouldFlip) {
                                val sp = liveSpecs
                                if (!sp.isNullOrEmpty()) {
                                    val idx = livePageIndex.coerceIn(0, sp.lastIndex)
                                    if (totalDrag < 0 && idx < sp.lastIndex) pageIndex = idx + 1
                                    if (totalDrag > 0 && idx > 0) pageIndex = idx - 1
                                }
                            }
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                ReaderPageView(ctx).also { pageViewRef.value = it }
            },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                val t = typo ?: return@AndroidView
                v.setContentInsets(topInsetPx, bottomInsetPx)
                val bp = currentBookPage
                if (bp != null) v.setPage(bp, t) else v.setColors(t)
            }
        )

        // loading 延迟显示: 内容就绪通常只需几十 ms(分页缓存命中),spinner 闪一下反而晃眼;
        // 超过 350ms 未就绪(冷缓存整本重排/大文件)才出现
        val contentReady = fullText != null && specs != null && currentBookPage != null
        var showLoading by remember(book.id) { mutableStateOf(false) }
        LaunchedEffect(contentReady) {
            if (contentReady) {
                showLoading = false
            } else {
                delay(350)
                showLoading = true
            }
        }
        if (!contentReady && showLoading) {
            if (cacheMissing) {
                Text(
                    "缓存缺失且重新转存失败，请删除后重新导入",
                    color = secondary,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                CircularProgressIndicator(color = fgColor, modifier = Modifier.align(Alignment.Center))
            }
        }

        // 菜单浮层: 顶栏
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
                    // 顶栏按钮区排除系统返回手势,避免边缘横滑误触
                    .systemGestureExclusion()
                    // 消费面板空白处点击,不穿透到底层阅读区的单击开关
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
                IconButton(onClick = { showToc = true }) {
                    Icon(Icons.AutoMirrored.Rounded.List, "目录", tint = fgColor)
                }
            }
        }

        // 菜单浮层: 底栏(进度条 + 设置入口)
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
                    // 进度条拖动排除系统返回手势: Slider 距屏缘仅 16dp,
                    // 手势导航下按住滑块横拖会被识别为返回而退出阅读页
                    .systemGestureExclusion()
                    // 消费面板空白处的点击: 不穿透到底层阅读区的单击开关(否则点面板会误关菜单)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var sliderValue by remember(menuVisible) { mutableStateOf<Float?>(null) }
                Slider(
                    modifier = Modifier.fillMaxWidth(),
                    value = sliderValue ?: percent.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = fgColor,
                        activeTrackColor = fgColor,
                        inactiveTrackColor = fgColor.copy(alpha = 0.22f)
                    ),
                    // 自绘轨道: M3 默认样式在滑块两侧留断口(gap),这里画无断口双
                    // 色轨道,已拖实色/未拖 22% 透明
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "${(percent * 100).roundToInt()}%",
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
            currentChapter = specs?.getOrNull(pageIndex)?.chapterIndex ?: 0,
            onChapterClick = { idx ->
                val target = specs?.indexOfFirst { it.chapterIndex == idx } ?: -1
                if (target >= 0) pageIndex = target
                showToc = false
            },
            onDismiss = { showToc = false }
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

// 当前页对应的全书百分比(供页脚与进度条)
private fun currentPercent(
    book: ReaderBook,
    specs: List<PageSpec>?,
    pageIndex: Int
): Double = specs?.getOrNull(pageIndex)?.let { BookPager.percentOf(book, it) } ?: 0.0
