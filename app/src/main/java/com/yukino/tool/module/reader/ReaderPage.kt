@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yukino.tool.module.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.core.view.WindowCompat
import com.yukino.tool.R
import com.yukino.tool.util.findActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.ActionMode
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Magnifier
import android.widget.Toast

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

    // ==================== 文字选择 ====================
    // 选区锚定全书字符偏移: 跨页/跨章统一,字号重排不漂移。翻页保留选区(跨页选择的前提)
    var selection by remember(book.id) { mutableStateOf<ReaderSelection?>(null) }
    var actionMode by remember { mutableStateOf<ActionMode?>(null) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // 选择激活时返回键先清选择再退出;声明在退出 BackHandler 之后(后注册优先接管)
    BackHandler(enabled = selection != null) {
        selection = null
        actionMode?.finish()
    }

    // 版式变化 → 整本重排,行模型全部失效,选区清除
    LaunchedEffect(typoKey) { if (selection != null) selection = null }
    // 菜单浮层弹出时收起文本工具栏(浮层盖在选区上,工具栏留着无意义)
    LaunchedEffect(menuVisible) { if (menuVisible) actionMode?.finish() }

    // 版心上下界(屏幕坐标): 与 ReaderPageView.drawPage 同一套常量
    val contentTopPx = topInsetPx +
        with(density) { (Typography.PAGE_PADDING_DP + Typography.TOP_GAP_DP).dp.toPx() }.roundToInt()
    val contentBottomY = (viewport?.height ?: 0) + topInsetPx -
        with(density) { Typography.FOOTER_GAP_DP.dp.toPx() }

    // 字体度量 + 测量(正文/标题两套字号),选择会话期间复用
    val selMetrics = remember(typo) {
        typo?.let { t ->
            val bodyP = android.text.TextPaint(android.text.TextPaint.ANTI_ALIAS_FLAG)
                .apply { textSize = t.fontPx }
            val titleP = android.text.TextPaint(android.text.TextPaint.ANTI_ALIAS_FLAG)
                .apply { textSize = t.fontPx * ChapterComposer.TITLE_SCALE }
            val fm = android.graphics.Paint.FontMetrics()
            bodyP.getFontMetrics(fm)
            val bodyAscent = -fm.ascent
            val bodyDescent = fm.descent
            titleP.getFontMetrics(fm)
            SelectionGeometry.Metrics(bodyAscent, bodyDescent, -fm.ascent, fm.descent) { text, title ->
                (if (title) titleP else bodyP).measureText(text)
            }
        }
    }

    // 选区可视化(版心坐标): 高亮矩形 + 手柄锚 + 跨页延续标志
    val selectionVisual = remember(currentBookPage, selection, selMetrics) {
        val bp = currentBookPage ?: return@remember null
        val sel = selection ?: return@remember null
        val m = selMetrics ?: return@remember null
        if (bp.spec.kind != PageKind.CONTENT) return@remember null
        SelectionGeometry.visual(bp, sel, m)
    }
    // 事件回调里读最新值,防闭包过期
    val selVisualRef = remember { mutableStateOf(selectionVisual) }
    selVisualRef.value = selectionVisual

    // 工具栏延迟弹出: 长按建选区后不能同步 startActionMode——选区坐标要等重组后才算好,
    // 同步弹时 onGetContentRect 拿不到矩形,系统会把工具栏放到屏幕顶部。
    // 标记 pending,等 selectionVisual 就绪的下一帧再弹,锚点一次到位。
    // (LaunchedEffect 在 showSelectionToolbar 定义之后,局部函数只能向后引用)
    var toolbarPending by remember { mutableStateOf(false) }

    fun showSelectionToolbar() {
        val v = pageViewRef.value ?: return
        actionMode?.finish()
        actionMode = v.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, "复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, 2, 0, "全选").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, 3, 0, "分享").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val sel = selection ?: return false
                val text = liveText ?: return false
                return when (item.itemId) {
                    1 -> {   // 复制
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("text", selectionText(liveBook, text, sel)))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        mode.finish()
                        selection = null
                        true
                    }
                    2 -> {   // 全选 = 选至本页首尾(整本全选无意义;配合拖到页缘驻留翻页可达任意范围)
                        val bp = livePage
                        if (bp != null && bp.lines.isNotEmpty()) {
                            selection = ReaderSelection(
                                bp.lines.first().lineStartGlobal,
                                bp.lines.last().lineStartGlobal + bp.lines.last().text.length,
                                anchorIsStart = false
                            )
                            mode.invalidateContentRect()
                        }
                        true
                    }
                    3 -> {   // 分享
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, selectionText(liveBook, text, sel))
                        }
                        context.startActivity(Intent.createChooser(send, "分享选中文字"))
                        mode.finish()
                        true
                    }
                    else -> false
                }
            }

            // 工具栏销毁不清选区: 保留高亮,点选区可再弹(系统惯例)
            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
            }

            // 浮动工具栏锚定选区包围盒(版心坐标 → 窗口坐标;页面 View 全屏,平移即窗口坐标)
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val sv = selVisualRef.value
                val t = liveTypo
                if (sv == null || t == null) {
                    super.onGetContentRect(mode, view, outRect)
                    return
                }
                var l = Float.MAX_VALUE
                var tp = Float.MAX_VALUE
                var r = -Float.MAX_VALUE
                var b = -Float.MAX_VALUE
                for (rc in sv.rects) {
                    l = min(l, rc.left)
                    tp = min(tp, rc.top)
                    r = max(r, rc.right)
                    b = max(b, rc.bottom)
                }
                outRect.set(
                    (l + t.marginPx).toInt(), (tp + contentTopPx).toInt(),
                    (r + t.marginPx).toInt() + 1, (b + contentTopPx).toInt() + 1
                )
            }
        }, ActionMode.TYPE_FLOATING)
    }

    // 选区坐标就绪后再弹工具栏(见 toolbarPending 注释)
    LaunchedEffect(selectionVisual) {
        if (toolbarPending && selectionVisual != null) {
            toolbarPending = false
            showSelectionToolbar()
        }
    }

    // 长按选词: 命中 → 词边界 → 建选区 + 触觉,工具栏由上面的 effect 延迟弹出
    fun beginSelection(offset: Offset) {
        val bp = livePage ?: return
        val t = liveTypo ?: return
        val m = selMetrics ?: return
        if (bp.spec.kind != PageKind.CONTENT || bp.lines.isEmpty()) return
        val hit = SelectionGeometry.hit(bp, offset.x - t.marginPx, offset.y - contentTopPx, m) ?: return
        val (s, e) = SelectionGeometry.wordRange(bp, hit.first, hit.second)
        selection = ReaderSelection(s, e, anchorIsStart = true)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        toolbarPending = true
    }

    // 选择模式下的驻留翻页: 手柄拖到版心上下缘,400ms 驻留翻一页,可连续;目标页优先邻页缓存
    fun flipForSelection(dir: Int) {
        val sp = liveSpecs ?: return
        val t = liveTypo ?: return
        val text = liveText ?: return
        val target = livePageIndex + dir
        if (target !in sp.indices) return
        neighborCache.getOrPut(target) { BookPager.materialize(liveBook, text, sp[target], t) }
        pageIndex = target
    }

    // 手柄拖动会话状态(不放 Compose state: 高频更新,无需触发重组)。
    // fromStart: 本会话抓的是起点柄还是终点柄——以手柄身份为准,
    // 不沿用上次手势残留的 anchorIsStart(否则先左拖再右拖会误坍缩掉左半选区)
    val selDrag = remember {
        object {
            var magnifier: Any? = null   // Magnifier(API 29+),Any 持有避免低版本类校验
            var dwellJob: Job? = null
            var pointerY = 0f
            var bandDir = 0
            var bandSince = 0L
            var fromStart = true
            var frozen: Long? = null   // 拖动开始时固定端的全书偏移(兜底校验用)
            var grabDy: Float? = null  // 按下时手指与锚点行中心的垂直偏移
        }
    }

    fun onHandleDragStart(fromStart: Boolean) {
        selDrag.fromStart = fromStart
        // 兜底记录: 本会话固定端的位置。一次只能有一个锚点在动,固定端必须全程钉死
        selDrag.frozen = if (fromStart) selection?.endGlobal else selection?.startGlobal
        selDrag.grabDy = null
        val v = pageViewRef.value
        if (Build.VERSION.SDK_INT >= 29 && v != null) selDrag.magnifier = Magnifier(v)
        selDrag.bandDir = 0
        selDrag.bandSince = 0
        selDrag.dwellJob = scope.launch {
            while (isActive) {
                delay(50)
                val dir = when {
                    selDrag.pointerY < contentTopPx -> -1
                    selDrag.pointerY > contentBottomY -> 1
                    else -> 0
                }
                val now = SystemClock.uptimeMillis()
                if (dir == 0) {
                    selDrag.bandDir = 0
                    continue
                }
                if (selDrag.bandDir != dir) {
                    selDrag.bandDir = dir
                    selDrag.bandSince = now
                    continue
                }
                if (now - selDrag.bandSince >= 400) {
                    // 翻页瞬间隐藏放大镜(挡视线且无观察价值),落定回到选字区由拖动回调重新 show
                    selDrag.magnifier?.let { if (Build.VERSION.SDK_INT >= 29) (it as Magnifier).dismiss() }
                    flipForSelection(dir)
                    selDrag.bandSince = now
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }

    fun onHandleDrag(fromStart: Boolean, px: Float, py: Float) {
        selDrag.fromStart = fromStart   // 每个事件按指针在两锚中点的左右刷新拖动端
        selDrag.pointerY = py
        // 手指抓的是挂在选字行下方的图标,天然比本行低: 用按下时的"手指-锚点行"偏移
        // 修正有效行位置——手指平移选本行字符,手指下移一行才换行
        if (selDrag.grabDy == null) {
            val sv = selVisualRef.value
            if (sv != null) {
                val h = if (fromStart) sv.startHandle else sv.endHandle
                val anchorCy = (h.top + h.bottom) / 2f + contentTopPx
                selDrag.grabDy = py - anchorCy
            }
        }
        val cyEff = py - (selDrag.grabDy ?: 0f)
        val bp = livePage ?: return
        val t = liveTypo ?: return
        val m = selMetrics ?: return
        val sel = selection ?: return
        val sp = liveSpecs ?: return
        // 驻留翻页后新页异步物化: 页描述与当前页号未对齐前不做命中,防止在旧页上算出错误偏移
        val idx = livePageIndex.coerceIn(0, sp.lastIndex)
        if (bp.spec != sp[idx] || bp.spec.kind != PageKind.CONTENT || bp.lines.isEmpty()) return
        val hit = SelectionGeometry.hit(bp, px - t.marginPx, cyEff, m)
        val hit2 = hit ?: return
        // 关键: 把本次会话的手柄身份同步到选区——上一次手势可能翻转过 anchorIsStart,
        // 不同步的话,抓右柄会被当成拖起点,导致原起点到原终点之间的选区被丢弃
        val sel2 = if (sel.anchorIsStart != fromStart) sel.copy(anchorIsStart = fromStart) else sel
        val updated = sel2.withAnchor(SelectionGeometry.globalAt(bp, hit2.first, hit2.second))
        // 兜底: 一次只允许一个锚点在动——固定端被连带移动、或结果为空选区时,丢弃本次更新
        val frozenIntact = selDrag.frozen == null ||
            updated.startGlobal == selDrag.frozen || updated.endGlobal == selDrag.frozen
        if (updated.isEmpty() || !frozenIntact) return
        if (updated != sel2) selection = updated
        val inBand = py < contentTopPx || py > contentBottomY
        selDrag.magnifier?.let {
            if (Build.VERSION.SDK_INT >= 29) {
                val mag = it as Magnifier
                if (inBand) mag.dismiss() else mag.show(px, py)
            }
        }
    }

    fun onHandleDragEnd() {
        selDrag.dwellJob?.cancel()
        selDrag.dwellJob = null
        selDrag.magnifier?.let { if (Build.VERSION.SDK_INT >= 29) (it as Magnifier).dismiss() }
        selDrag.magnifier = null
        // 坐标已就绪直接弹;驻留翻页刚落账时选区可视未更新,交给 effect 延迟弹
        if (selVisualRef.value != null) showSelectionToolbar() else toolbarPending = true
    }
    // ==================== 文字选择结束 ====================

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)                    // 背景延伸到系统栏后面
            .onSizeChanged {
                viewport = IntSize(it.width, (it.height - topInsetPx - bottomInsetPx).coerceAtLeast(0))
            }
            // 单击/长按: 无选择时单击开关菜单、长按选词;有选择时单击弹工具栏(选区内)或清除(选区外)。
            // key 带选择态: 模式切换重建检测器,闭包取到最新行为
            // 单击/长按检测器: key 固定 Unit,不在选择态切换时重建——
            // 长按建选区会改 selection,若以其为 key,长按抬指会被重建后的新检测器
            // 当成一次新单击(菜单误开/选区误清)。分支在事件时实时读最新状态
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        if (selection == null) {
                            menuVisible = !menuVisible
                        } else {
                            val sv = selVisualRef.value
                            val t = liveTypo
                            val inside = sv != null && t != null &&
                                sv.contains(offset.x - t.marginPx, offset.y - contentTopPx, 24f)
                            if (inside) showSelectionToolbar() else {
                                selection = null
                                actionMode?.finish()
                            }
                        }
                    },
                    onLongPress = { offset ->
                        if (selection == null && !menuVisible) beginSelection(offset)
                    }
                )
            }
            .pointerInput(menuVisible, selection != null, typoKey, specs) {
                // 菜单打开或选择激活期间翻页手势让位(选择下翻页只经由手柄驻留自动翻页)
                if (menuVisible || selection != null) return@pointerInput
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
                // 选择高亮: 版心坐标直接交给 View(与行绘制同一平移)
                val sv = selectionVisual
                val hlColor = fgColor.copy(alpha = 0.25f).toArgb()
                if (sv != null) {
                    v.setSelection(
                        sv.rects.map { RectF(it.left, it.top, it.right, it.bottom) },
                        sv.extendsTop, sv.extendsBottom, hlColor
                    )
                } else {
                    v.setSelection(null, false, false, hlColor)
                }
            }
        )

        // 选择手柄: 左右倾斜水滴挂在各自锚点下方(参考系统选区柄),
        // 共用一个触摸区,按指针在两锚点中点的左右判定拖哪个柄——
        // 单字选中时两柄贴近,分立热区会互相遮挡导致抓错,合并后按位置判定不会错
        if (selectionVisual != null && typo != null && !menuVisible) {
            val t = typo
            val sv = selectionVisual
            val handleColor = Color(0xFF26A69A)   // 水滴柄用固定强调色(青),深浅主题下都醒目
            SelectionHandles(
                startX = sv.startHandle.left + t.marginPx,
                endX = sv.endHandle.left + t.marginPx,
                startY = sv.startHandle.bottom + contentTopPx,
                endY = sv.endHandle.bottom + contentTopPx,
                color = handleColor,
                onDragStart = ::onHandleDragStart,
                onDrag = ::onHandleDrag,
                onDragEnd = ::onHandleDragEnd
            )
        }

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


// 选择双柄: 左右倾斜水滴图标挂在各自锚点下方(参考系统选区柄样式),
// 共用一个触摸区——按下/拖动时按指针在两锚点中点的左右判定拖的是哪个柄,
// 单字选中时两柄贴近也不会互相遮挡抓错。图标只作视觉,拖动仅在此触摸区生效
@Composable
private fun SelectionHandles(
    startX: Float,
    endX: Float,
    startY: Float,
    endY: Float,
    color: Color,
    onDragStart: (Boolean) -> Unit,
    onDrag: (fromStart: Boolean, px: Float, py: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val density = LocalDensity.current
    // 水滴几何: 锚点为杆的尖端,圆头沿 45° 斜向外下方;热区把圆头和热区半径都包进来
    val dropR = with(density) { 14.dp.toPx() }
    val dropDx = with(density) { 24.dp.toPx() }   // 圆心相对锚点的水平偏移(左柄向左 45°,右柄向右 45°)
    val dropDy = with(density) { 24.dp.toPx() }   // 圆心相对锚点的垂直偏移(向下)
    val padPx = with(density) { 16.dp.toPx() }    // 图标外侧的热区半径
    val left = minOf(startX - dropDx, endX - dropDx) - dropR - padPx
    val right = maxOf(startX + dropDx, endX + dropDx) + dropR + padPx
    val top = minOf(startY, endY)
    val wPx = right - left
    val height = (maxOf(startY, endY) - top) + dropDy + dropR + padPx

    // 盒随选区移动,窗口坐标 = 盒原点(实时)+ 盒内指针位置,两者此消彼长保持连续
    var origin by remember { mutableStateOf<Offset?>(null) }
    val liveStartX by rememberUpdatedState(startX)
    val liveEndX by rememberUpdatedState(endX)
    // 拖动端在按下瞬间判定一次并固定整个会话: 逐事件判定会在越过中线时来回翻转,
    // 造成选区坍缩/状态抖动。会话内越过另一端的情形由 withAnchor 的翻转语义处理
    var sessionFromStart by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { (wPx / density.density).dp }, with(density) { (height / density.density).dp })
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        origin?.let { o ->
                            val px = o.x + pos.x
                            val fromStart = px < (liveStartX + liveEndX) / 2f
                            sessionFromStart = fromStart
                            onDragStart(fromStart)
                            onDrag(fromStart, px, o.y + pos.y)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        origin?.let { o ->
                            val px = o.x + change.position.x
                            onDrag(sessionFromStart, px, o.y + change.position.y)
                        }
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            }
    ) {
        // 系统样式水滴矢量图: 尖端贴锚点;绕尖端向外倾斜 45°(左柄向左倒,右柄向右倒)
        val iconWTpx = with(density) { 28.dp.toPx() }
        val iconHTpx = with(density) { 36.dp.toPx() }
        fun handleModifier(anchorX: Float, relY: Float, tilt: Float): Modifier = Modifier
            .offset { IntOffset((anchorX - left - iconWTpx / 2).roundToInt(), relY.roundToInt()) }
            .size(24.dp, 30.dp)
            .graphicsLayer {
                rotationZ = tilt
                transformOrigin = TransformOrigin(0.5f, 0f)   // 绕尖端旋转,尖端始终贴锚点
            }
        Image(
            painter = painterResource(R.drawable.reader_handle_drop),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = handleModifier(startX, startY - top, 45f)
        )
        Image(
            painter = painterResource(R.drawable.reader_handle_drop),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = handleModifier(endX, endY - top, -45f)
        )
    }
}
