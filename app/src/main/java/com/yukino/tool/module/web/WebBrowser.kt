package com.yukino.tool.module.web

import WebBox
import WebBoxFunc
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.yukino.tool.TAG
import com.yukino.tool.util.rememberCurrentActivity
import kotlinx.coroutines.launch
import kotlin.math.pow
import java.util.UUID
import kotlin.math.abs

class WebWrapper(

    val key: String = UUID.randomUUID().toString(),

    val initUrl: String,

    val content: WebBoxFunc,

    //Web组件创建后上交的webview引用, 关闭box时负责销毁释放
    var webview: CustomWebView? = null,

    //退场进度: 1=正常位置, 关闭时1→0滑出滑出后销毁; 新开不做入场动画, 初始即为1
    val appear: Animatable<Float, AnimationVector1D> = Animatable(1f),

    //退场动画进行中标记: 防止重复关闭, 退场完成后才真正销毁移除
    var closing: Boolean = false,
) {

    //稳定槽位: 卡片在列表中的下标(浮点), 删卡后下标变化时用动画平滑过渡, 避免瞬移
    val slot = Animatable(0f)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WebBrowser(initialUrl: String? = null, openDownloadsTick: Int = 0) {

    val webBoxes = remember {
        mutableStateListOf<WebWrapper>()
    }

    var showList by remember {
        mutableStateOf(false)
    }

    //下载页面(浏览器级弹窗): 菜单入口与下载完成通知点击都会打开
    var showDownloads by remember {
        mutableStateOf(false)
    }

    var showWebWrapper by remember {
        mutableStateOf<WebWrapper?>(null)
    }

    //只允许同站打开: 浏览器级共享状态，所有box(含新开的)统一生效
    var onlyOpenSameSite by remember {
        mutableStateOf(false)
    }

    val currentActivity = rememberCurrentActivity() as ComponentActivity

    val currentCoroutineScope = rememberCoroutineScope()

    //容器宽高: 卡片滑入/滑出距离与堆叠间距的基准
    val containerHeight = remember { mutableStateOf(0f) }
    val containerWidth = remember { mutableStateOf(0f) }

    val density = LocalDensity.current

    //堆叠布局参数: 焦点卡片距顶部base
    fun stackBase(): Float = containerHeight.value.takeIf { it > 0f }?.let { it * 0.12f } ?: 800f

    //初始间距: 30%屏高; 最小间距: 与标题栏高度一致(32dp), 收拢时卡片像标签页一样齐整排列
    fun initialGap(): Float = containerHeight.value.takeIf { it > 0f }?.let { it * 0.3f } ?: 720f
    fun titleGap(): Float = with(density) { 32.dp.toPx() }
    fun topGap(): Float = with(density) { 36.dp.toPx() }

    // 浏览历史: 打开页面即记录, 持久化不设上限
    val historyItems = remember {
        mutableStateListOf<WebHistoryItem>().apply { addAll(WebHistoryStore.load(currentActivity)) }
    }

    // 记录浏览历史: 每次导航(非reload)记一条, 新的在前; 同URL旧记录删除避免重复; 标题延迟补抓后回填并落盘
    fun recordHistory(webview: CustomWebView, url: String?, isReload: Boolean) {
        if (isReload || url.isNullOrBlank()) return
        if (historyItems.firstOrNull()?.url == url) return
        historyItems.removeAll { it.url == url }
        val item = WebHistoryItem(url = url, title = webview.title ?: "", time = System.currentTimeMillis())
        historyItems.add(0, item)
        WebHistoryStore.save(currentActivity, historyItems)
        currentCoroutineScope.launch {
            kotlinx.coroutines.delay(1500)
            val t = runCatching { webview.title }.getOrNull()
            if (!t.isNullOrBlank()) {
                val i = historyItems.indexOfFirst { it.url == url && it.title.isBlank() }
                if (i >= 0) {
                    historyItems[i] = historyItems[i].copy(title = t)
                    WebHistoryStore.save(currentActivity, historyItems)
                }
            }
        }
    }

    // favicon回填: 压缩为48px入库, 只回填还没有图标的条目
    fun saveHistoryIcon(url: String, icon: Bitmap) {
        val encoded = WebHistoryStore.compressIcon(icon) ?: return
        val i = historyItems.indexOfFirst { it.url == url && it.icon == null }
        if (i >= 0) {
            historyItems[i] = historyItems[i].copy(icon = encoded)
            WebHistoryStore.save(currentActivity, historyItems)
        }
    }

    //堆叠自由滑动偏移: 0=完整铺开, 越负越向上; 无档位无吸附, 松手停在原地
    val stackOffset = remember { mutableStateOf(0f) }

    //滑动下界: 最后一张卡片也到达自己的挤压位(只露标题); 不足两张时为0
    fun minStackOffset(): Float =
        if (webBoxes.size > 1) -(stackBase() - topGap()) - (webBoxes.size - 1) * (initialGap() - titleGap()) else 0f

    //当前间距: 随滑动进度从初始间距线性压缩到最小间距
    fun currentGap(): Float {
        val min = minStackOffset()
        if (min == 0f) return initialGap()
        return lerp(initialGap(), titleGap(), (stackOffset.value / min).coerceIn(0f, 1f))
    }

    //堆叠⇄浏览态过渡进度: 0=浏览态(全屏), 1=堆叠态; 所有卡片的位置/缩放/透明度在其上插值
    val stackProgress = remember { Animatable(0f) }

    fun addWebBox(url: String) {
        val webWrapper = WebWrapper(
            initUrl = url,
            content = WebBox
        )
        webBoxes.add(webWrapper)
        //新卡片直接落在自己的槽位上
        currentCoroutineScope.launch {
            webWrapper.slot.snapTo((webBoxes.size - 1).coerceAtLeast(0).toFloat())
        }
        showWebWrapper = webWrapper
    }

    // 关闭单个webview: 先播滑出淡出动画, 结束后移除视图树并destroy释放; 若关的是当前显示的则立即切到上一个
    // fromUser: 用户主动关闭(如按钮)时全部关完会自动重开首页; 返回键触发的关闭则直接退出浏览器页
    fun closeWebBox(box: WebWrapper, fromUser: Boolean = false) {
        if (box.closing) return
        box.closing = true
        val idx = webBoxes.indexOf(box)
        if (showWebWrapper == box) {
            //优先切到上一个, 没有则切下一个; 关闭动画期间box仍在列表里
            showWebWrapper = webBoxes.getOrNull(idx - 1) ?: webBoxes.getOrNull(idx + 1)
        }
        currentCoroutineScope.launch {
            box.appear.animateTo(0f, tween(250, easing = FastOutLinearInEasing))
            box.webview?.let { wv ->
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
                box.webview = null
            }
            webBoxes.remove(box)
            if (webBoxes.isEmpty()) {
                if (fromUser) {
                    //用户主动关闭全部卡片: 自动新开默认主页, 浏览器不退出
                    addWebBox("https://www.google.com/ncr")
                    showList = false
                    stackOffset.value = 0f
                    stackProgress.animateTo(0f, spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ))
                } else {
                    //返回键触发的关闭: 直接退出浏览器页
                    currentActivity.finish()
                }
            }
            Log.i(TAG, "browser 关闭webview: 剩余=${webBoxes.count { !it.closing }}")
        }
    }


    // 浏览器级返回: 回到上一个webview, 并清空当前及其后打开的所有webview
    val onBackPressedCallback = remember {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val current = showWebWrapper
                val idx = webBoxes.indexOf(current)
                if (idx <= 0) {
                    isEnabled = false
                    return
                }
                //清空当前及其后打开的所有webview(逐个走关闭动画后销毁)
                webBoxes.drop(idx).forEach { closeWebBox(it) }
                isEnabled = !showList && webBoxes.count { !it.closing } > 1
                Log.i(TAG, "browser 回退: 清空后续webview")
            }
        }
    }

    // webview数量/堆叠视图状态变化时同步返回回调可用性
    LaunchedEffect(webBoxes.size, showList, containerHeight.value) {
        onBackPressedCallback.isEnabled = !showList && webBoxes.size > 1
        //卡片减少后偏移可能越界, 收回到合法范围
        stackOffset.value = stackOffset.value.coerceIn(minStackOffset(), 0f)
    }

    LaunchedEffect(showWebWrapper) {
        if (showWebWrapper != null) {
            onBackPressedCallback.isEnabled = !showList && webBoxes.size > 1
        }
    }

    DisposableEffect(Unit) {
        currentActivity.onBackPressedDispatcher.addCallback(onBackPressedCallback)
        onDispose {
            onBackPressedCallback.remove()
        }
    }

    //切出App再切回时, WebView的渲染surface可能丢失导致页面空白: 恢复时onResume+invalidate强制重绘
    //注意ON_RESUME时surface可能尚未重建完成, 需延迟再补绘几次, 否则真机上后台卡片仍是空白
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webBoxes.size) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                webBoxes.forEach { b ->
                    b.webview?.let { wv ->
                        wv.onResume()
                        wv.invalidate()
                        wv.postDelayed({ wv.invalidate() }, 150)
                        wv.postDelayed({ wv.invalidate() }, 500)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        // 下载列表从库恢复(下载中遗留显示为已中断)
        WebDownloader.loadPersisted(currentActivity)
        // 外部"用浏览器打开"传入的链接优先; 否则打开默认主页
        addWebBox(initialUrl ?: "https://www.google.com/ncr")
    }

    //下载通知点击: 版本号变化即弹出下载页面
    LaunchedEffect(openDownloadsTick) {
        if (openDownloadsTick > 0) {
            WebDownloader.loadPersisted(currentActivity)
            showDownloads = true
        }
    }

    // 卡片变换: 浏览⇄堆叠在stackProgress上插值; 卡片尺寸统一, 层次感靠阴影; 关闭沿appear滑出
    // 在graphicsLayer lambda里读动画状态, 拖拽/过渡期间纯渲染层更新, 不触发重组
    fun cardTransform(index: Int, box: WebWrapper, gr: androidx.compose.ui.graphics.GraphicsLayerScope) {
        val p = stackProgress.value
        val exit = containerHeight.value.takeIf { it > 0f } ?: 2000f
        //浏览态: 焦点卡片全屏在顶(状态栏避让由标题栏padding处理), 其余停在屏幕外
        val restY = if (box == showWebWrapper || box.closing) 0f else exit
        //所有卡片按同一进度在"铺开位"和"标题位"之间同时插值, 一起动一起停
        val offset = stackOffset.value
        val slot = box.slot.value
        val spreadY = stackBase() + slot * initialGap()
        val squeezedY = topGap() + slot * titleGap()
        val min = minStackOffset()
        val u = if (min != 0f) (offset / min).coerceIn(0f, 1f) else 0f
        val stackedY = lerp(spreadY, squeezedY, u)
        gr.translationY = lerp(restY, stackedY, p) + (1f - box.appear.value) * exit
        gr.scaleX = 1f
        gr.scaleY = 1f
        //原生外阴影: 必须给shape才有outline, elevation阴影才会生效并投到后一张卡片上
        gr.shape = RectangleShape
        gr.shadowElevation = lerp(0f, 6f, p) + slot * 2f
        //注意: 本层alpha必须保持1, alpha!=1触发离屏合成会导致阴影不绘制; 淡出由cardAlpha层负责
    }

    //卡片透明度: 无渐变淡出, 只跟随关闭动画(appear 1→0)
    fun cardAlpha(index: Int, box: WebWrapper): Float = box.appear.value

    //原生滚动状态: 拖拽+惯性(fling)全部走系统滚动机制
    val stackScrollable = rememberScrollableState { delta ->
        //delta向下为正, 向上滑delta为负 -> offset变负; 上界0下界minStackOffset
        stackOffset.value = (stackOffset.value + delta).coerceIn(minStackOffset(), 0f)
        delta
    }

    Box(
        modifier = Modifier
            //键盘弹出时整体内容上移到键盘上方: 边到边模式下窗口不自动resize, 网页内底部输入框才不被遮挡
            .imePadding()
            .onSizeChanged {
                containerHeight.value = it.height.toFloat()
                containerWidth.value = it.width.toFloat()
            }
            .scrollable(
                orientation = Orientation.Vertical,
                enabled = showList,
                //默认flingBehavior即系统spline衰减惯性, 与原生滚动手感一致
                state = stackScrollable
            )
    ) {
        webBoxes.forEachIndexed { index, it ->
            key(it.key) {
                val wrapper = it
                val isCurrent = !showList && showWebWrapper == it
                //退场中的卡片置顶显示, 滑出时不被其他卡片盖住
                val zIndex = when {
                    it.closing -> webBoxes.size + 1
                    isCurrent -> webBoxes.size
                    else -> index
                }
                //下标变化(删卡/加卡)时槽位平滑过渡, 卡片不再瞬移
                LaunchedEffect(it, index) {
                    it.slot.animateTo(index.toFloat(), spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ))
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { cardTransform(index, it, this) }
                        //顶部细阴影: elevation阴影受顶光限制在顶边上方几乎不可见, 手动画一条12px渐变补足
                        .drawWithContent {
                            drawContent()
                            val sp = stackProgress.value
                            if (sp > 0f) {
                                val h = 12f
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.15f * sp),
                                        startY = -h,
                                        endY = 0f
                                    ),
                                    topLeft = Offset(x = 0f, y = -h),
                                    size = Size(size.width, h)
                                )
                            }
                        }
                        .graphicsLayer { alpha = cardAlpha(index, it) }
                        .zIndex(zIndex.toFloat())
                ) {
                    it.content(
                        it.initUrl,
                        {
                            addWebBox(it)
                        },
                        {
                            //进入堆叠态: 从当前位置开始自由滑动; 当前卡片zIndex最高叠在最上
                            showList = true
                            Log.i(TAG, "WebBrowser: ${showList}, ${showWebWrapper?.let { w -> webBoxes.indexOf(w) }}")
                            currentCoroutineScope.launch {
                                stackProgress.animateTo(1f, spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ))
                            }
                        },
                        webBoxes.size,
                        index,
                        isCurrent,
                        onlyOpenSameSite,
                        { onlyOpenSameSite = it },
                        {
                            // 本webview网页历史耗尽: 关闭它,回到上一个webview;没有webview了才关闭页面
                            closeWebBox(it)
                        },
                        { webview, url, isReload -> recordHistory(webview, url, isReload) },
                        { url, icon -> saveHistoryIcon(url, icon) },
                        { closeWebBox(it) },
                        { created -> it.webview = created },
                        { showDownloads = true }
                    )
                    if (showList) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = stackProgress.value }
                        ) {
                            // 点击卡片区域: 选中并播放展开动画回到浏览态; 左右横滑: 弹出关闭确认
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(color = Color.Transparent)
                                    .clickable {
                                        showList = false
                                        showWebWrapper = it
                                        currentCoroutineScope.launch {
                                            stackProgress.animateTo(0f, spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            ))
                                        }
                                    }
                            ) {}
                            // ✕ 关闭: 与标题栏(32dp)垂直居中对齐
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 4.dp, end = 8.dp)
                                    .background(color = Color(0xFF6B6B6B), shape = CircleShape)
                                    .size(24.dp)
                                    .clickable { closeWebBox(it, fromUser = true) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        //下载页面弹窗(菜单入口/通知点击打开)
        if (showDownloads) {
            DownloadsDialog(onDismiss = { showDownloads = false })
        }
    }

}

//下载页面: 任务列表, 每条含进度/状态与暂停-继续-取消-打开操作
@Composable
fun DownloadsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val states by WebDownloader.states.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        title = { Text(text = "下载管理(${states.size})") },
        text = {
            if (states.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无下载任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(items = states, key = { _, s -> s.url }) { _, s ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = s.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val progressText = when (s.status) {
                                WebDownloader.Status.RUNNING ->
                                    "${WebDownloader.formatBytes(s.offset)}" +
                                        (if (s.total > 0) "/${WebDownloader.formatBytes(s.total)}" else "") +
                                        " · ${WebDownloader.formatBytes(s.speedBps)}/s"
                                WebDownloader.Status.PAUSED -> "已暂停 · ${WebDownloader.formatBytes(s.offset)}"
                                WebDownloader.Status.INTERRUPTED -> "已中断 · ${WebDownloader.formatBytes(s.offset)}"
                                WebDownloader.Status.FAILED -> "失败: ${s.error ?: "未知错误"}"
                                WebDownloader.Status.DONE -> "已完成 · ${if (s.total > 0) WebDownloader.formatBytes(s.total) else WebDownloader.formatBytes(s.offset)}"
                            }
                            Text(
                                text = progressText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (s.status == WebDownloader.Status.RUNNING) {
                                LinearProgressIndicator(
                                    progress = { if (s.total > 0) (s.offset.toFloat() / s.total) else 0f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                )
                            }
                            Row {
                                when (s.status) {
                                    WebDownloader.Status.RUNNING ->
                                        TextButton(onClick = { WebDownloader.pause(s.url) }) { Text("暂停") }
                                    WebDownloader.Status.PAUSED, WebDownloader.Status.INTERRUPTED ->
                                        TextButton(onClick = { WebDownloader.resume(context, s.url) }) { Text("继续") }
                                    WebDownloader.Status.DONE ->
                                        TextButton(onClick = { WebDownloader.openDownloaded(context, s) }) { Text("打开") }
                                    WebDownloader.Status.FAILED -> {}
                                }
                                if (s.status != WebDownloader.Status.DONE && s.status != WebDownloader.Status.FAILED) {
                                    TextButton(onClick = {
                                        WebDownloader.cancelDownload(context, s.url, s.notifyId)
                                    }) { Text("取消") }
                                }
                            }
                        }
                    }
                }
            }
        },
        //M3 AlertDialog的confirmButton是必填: 不渲染任何内容实现无按钮
        confirmButton = {}
    )
}
