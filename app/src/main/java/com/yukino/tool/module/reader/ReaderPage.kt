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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.core.view.WindowCompat
import com.yukino.tool.R
import com.yukino.tool.module.reader.common.BookContent
import com.yukino.tool.module.reader.common.BookFormat
import com.yukino.tool.module.reader.common.BookPage
import com.yukino.tool.module.reader.common.BookPager
import com.yukino.tool.module.reader.common.ChapterComposer
import com.yukino.tool.module.reader.common.PageKind
import com.yukino.tool.module.reader.common.PageSpec
import com.yukino.tool.module.reader.common.ReaderBook
import com.yukino.tool.module.reader.common.ReaderSettings
import com.yukino.tool.module.reader.common.ReaderPageView
import com.yukino.tool.module.reader.common.Typography
import com.yukino.tool.module.reader.epub.EpubImporter
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
    onInitComplete: (ReaderBook) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current

    // 内容源(txt 全文 / epub 章节文件): null=加载中(登记态初始化/缓存重建)
    var content by remember(book.id) { mutableStateOf<BookContent?>(null) }
    var initStage by remember(book.id) { mutableStateOf<String?>(null) }
    var loadError by remember(book.id) { mutableStateOf<String?>(null) }
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
    var showJumpPage by remember { mutableStateOf(false) }
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

    // 加载内容源(按格式分派到各自主流程);登记态书在此完成懒初始化(转码/解压+章节解析)
    LaunchedEffect(book.id) {
        pageViewRef.value?.clearImages()   // 图片缓存不跨书复用
        loadError = null
        content = try {
            val (c, refreshed) = BookContents.load(context, book) { initStage = it }
            if (refreshed != book) onInitComplete(refreshed)   // 同步回上层内存态, 防进度落盘覆盖初始化结果
            c
        } catch (e: BookInitException) {
            loadError = e.message ?: "打开失败"
            null
        } catch (e: Exception) {
            loadError = "打开失败: ${e.message ?: "未知错误"}"
            null
        }
        initStage = null
    }

    val typo = viewport?.let {
        Typography.resolve(density.density, settings, it.width, it.height)
    }
    // 版式键: 影响断行/颜色的全部字段,变化即整本重建
    val typoKey = typo?.let {
        listOf(
            it.fontPx, it.lineExtraPx, it.paraExtraPx, it.indentPx, it.marginPx,
            it.textWidth, it.textHeight, it.justify, it.fgColor, it.bookSpacing,
            Typography.BREAK_STRATEGY_VERSION   // 断行算法升级时使旧缓存失效
        ).hashCode()
    }

    // 手势闭包防过期: 拖拽中读最新状态
    val liveBook by rememberUpdatedState(book)
    val liveContent by rememberUpdatedState(content)
    val liveTypo by rememberUpdatedState(typo)
    val liveSpecs by rememberUpdatedState(specs)
    val livePageIndex by rememberUpdatedState(pageIndex)
    val livePage by rememberUpdatedState(currentBookPage)

    // 全书页目录: 文本/视口/版式任一变化 → 后台整本重算,按锚点重定位(打开书时锚点=持久化进度)。
    // 分页结果持久化缓存(ReaderStore.specs): 同一本书版式未变时二次进入直接命中,免整本重排
    LaunchedEffect(content, typoKey) {
        val cnt = content ?: return@LaunchedEffect
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
        val result = BookPager.buildSpecs(cnt, t)
        specsTypoKey = key
        specs = result
        pageIndex = BookPager.locatePage(result, anchor)
        withContext(Dispatchers.IO) { ReaderStore.saveSpecs(context, book.id, key, book.totalChars, result) }
    }

    // 装饰章(扉页等 CSS 排版页,章首段带装饰盒): 该章走 WebView 按原书样式呈现,
    // 排版引擎不物化。decorativeOn 立即切换视图,docUrl 由 spine 反查后台补齐
    var decorativeOn by remember(book.id) { mutableStateOf(false) }
    var decorativeDocUrl by remember(book.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(specs, pageIndex, book.id) {
        val ci = specs?.getOrNull(pageIndex)?.chapterIndex
        decorativeOn = false
        decorativeDocUrl = null
        if (book.format != BookFormat.EPUB || ci == null || ci !in book.chapters.indices) {
            return@LaunchedEffect
        }
        val probe = withContext(Dispatchers.IO) {
            runCatching { EpubImporter.isDecorativeChapter(context, book.id, ci) }.getOrDefault(false)
        }
        if (!probe) return@LaunchedEffect
        decorativeOn = true
        val info = withContext(Dispatchers.IO) {
            runCatching { EpubImporter.findDecorativeSourceDoc(context, book.id, ci) }.getOrNull()
        }
        if (info != null && specs?.getOrNull(pageIndex)?.chapterIndex == ci) {
            val (doc, root) = info
            decorativeDocUrl = "https://book.local/" +
                doc.relativeTo(root).invariantSeparatorsPath
        }
    }

    // 物化当前页: 目录/页号/版式就绪 → 后台构建
    LaunchedEffect(specs, pageIndex, typoKey) {
        val sp = specs ?: return@LaunchedEffect
        val t = typo ?: return@LaunchedEffect
        val cnt = content ?: return@LaunchedEffect
        if (sp.isEmpty()) return@LaunchedEffect
        val idx = pageIndex.coerceIn(0, sp.lastIndex)
        currentBookPage = withContext(Dispatchers.Default) {
            BookPager.materialize(cnt, sp[idx], t, idx, sp.size)
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
        val cnt = content ?: return@LaunchedEffect
        if (sp.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            for (off in intArrayOf(1, -1)) {
                val i = pageIndex + off
                if (i !in sp.indices || neighborCache.containsKey(i)) continue
                launch(Dispatchers.Default) {
                    neighborCache[i] = BookPager.materialize(cnt, sp[i], t, i, sp.size)
                }
            }
        }
    }

    // 进度上报(内存态,ReaderApp 防抖落盘)
    LaunchedEffect(specs, pageIndex) {
        val sp = specs ?: return@LaunchedEffect
        val spec = sp.getOrNull(pageIndex) ?: return@LaunchedEffect
        onProgress(spec.globalCharOffset, BookPager.percentOf(content ?: return@LaunchedEffect, spec))
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
    val percent = currentPercent(content, specs, pageIndex)

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

    // 脚注弹层: (noteId, 内容)。角标点击命中后弹出,关闭即回原位(不改变页面状态)
    var footnoteShow by remember(book.id) { mutableStateOf<Pair<String, String>?>(null) }

    // 出版信息页(四期实验项): 书籍元数据弹层,菜单顶栏"信息"入口
    var showBookInfo by remember(book.id) { mutableStateOf(false) }

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

    // tap → 角标命中: 版心坐标 → (行,字符) → 全书偏移 → 章脚注表;未命中返回 null。
    // 占位符(U+FFFC)的点击度量不含 ReplacementSpan 图标宽,字符吸附可能偏多个字符——
    // 先做"行内角标偏移差匹配"(容差 3 字符),未中再退常规 ±1 邻域
    fun footnoteHitAt(offset: Offset): Pair<String, String>? {
        val bp = livePage ?: return null
        val t = liveTypo ?: return null
        val cnt = liveContent ?: return null
        val m = selMetrics ?: return null
        if (bp.spec.kind != PageKind.CONTENT) return null
        val hit = SelectionGeometry.hit(bp, offset.x - t.marginPx, offset.y - contentTopPx, m) ?: return null
        val line = bp.lines[hit.first]
        val global = SelectionGeometry.globalAt(bp, hit.first, hit.second)
        if (line.inlineImages.isNotEmpty()) {
            val nearest = line.inlineImages.minByOrNull {
                kotlin.math.abs(global - (line.lineStartGlobal + it.charIdx))
            }
            if (nearest != null &&
                kotlin.math.abs(global - (line.lineStartGlobal + nearest.charIdx)) <= 3
            ) {
                return cnt.footnoteAt(line.lineStartGlobal + nearest.charIdx)
            }
            return null   // 点击在本行但不在角标容差内,不弹菜单也不误触脚注
        }
        return cnt.footnoteAt(global)
            ?: cnt.footnoteAt(global - 1)
            ?: cnt.footnoteAt(global + 1)
    }

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
                val cnt = liveContent ?: return false
                return when (item.itemId) {
                    1 -> {   // 复制
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("text", selectionText(cnt, sel)))
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
                            putExtra(Intent.EXTRA_TEXT, selectionText(cnt, sel))
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
        val cnt = liveContent ?: return
        val target = livePageIndex + dir
        if (target !in sp.indices) return
        val neighbor = neighborCache.getOrPut(target) {
            BookPager.materialize(cnt, sp[target], t, target, sp.size)
        }
        // 与手势翻页同一套动画: 摆好拖拽层后提交收尾,整页顺势滑入/滑出
        pageViewRef.value?.let { v ->
            livePage?.let { cur ->
                v.showDrag(cur, neighbor, dir, 0f)
                v.animateDragEnd(true)
            }
        }
        pageIndex = target
    }

    // 手柄拖动会话状态(不放 Compose state: 高频更新,无需触发重组)。
    // fromStart: 本会话抓的是起点柄还是终点柄——以手柄身份为准,
    // 不沿用上次手势残留的 anchorIsStart(否则先左拖再右拖会误坍缩掉左半选区)
    var selDragActive by remember { mutableStateOf(false) }
    val selDrag = remember {
        object {
            var magnifier: Any? = null   // Magnifier(API 29+),Any 持有避免低版本类校验
            var dwellJob: Job? = null
            var pointerX = 0f
            var pointerY = 0f
            var sawContent = false   // 抓柄后指针是否进过内容区(防边缘抓柄误触发驻留)
            var bandDir = 0
            var bandSince = 0L
            var fromStart = true
            var frozen: Long? = null   // 拖动开始时固定端的全书偏移(兜底校验用)
            var grabDy: Float? = null  // 按下时手指与锚点行中心的垂直偏移
        }
    }

    fun onHandleDragStart(fromStart: Boolean) {
        selDragActive = true
        selDrag.fromStart = fromStart
        // 把手柄身份同步到选区(仅此一次): 上一次手势可能翻转过 anchorIsStart,
        // 不同步的话,抓左柄会被当成拖终点,导致原起点到原终点之间的选区被丢弃。
        // 之后的翻转交给 withAnchor 自然进行——逐事件强行重同步会让翻转后的
        // 固定端丢失,后续更新全被 frozen 兜底拒绝,拖动卡死
        selection?.let {
            if (it.anchorIsStart != fromStart) selection = it.copy(anchorIsStart = fromStart)
        }
        // 兜底记录: 本会话固定端的位置。一次只能有一个锚点在动,固定端必须全程钉死
        selDrag.frozen = if (fromStart) selection?.endGlobal else selection?.startGlobal
        selDrag.grabDy = null
        val v = pageViewRef.value
        if (Build.VERSION.SDK_INT >= 29 && v != null) selDrag.magnifier = Magnifier(v)
        selDrag.bandDir = 0
        selDrag.bandSince = 0
        // 四向驻留带: 上下缘钳制到页首/页尾, 左右缘钳制到手指所在行的行首/行尾。
        // 左右带仅在指针进过内容区后武装——首/尾行选区的手柄本来就在版心边缘,
        // 抓柄静止时不能误触发驻留
        val dwellLeft = (liveTypo?.marginPx ?: 0).toFloat()
        val dwellRight = (viewport?.width ?: 0) - (liveTypo?.marginPx ?: 0).toFloat()
        selDrag.dwellJob = scope.launch {
            while (isActive) {
                delay(50)
                val dir = when {
                    selDrag.pointerY < contentTopPx -> -1
                    selDrag.pointerY > contentBottomY -> 1
                    selDrag.sawContent && selDrag.pointerX < dwellLeft -> -1
                    selDrag.sawContent && selDrag.pointerX > dwellRight -> 1
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
                if (now - selDrag.bandSince >= 800) {
                    // 选区端点必须已追到钳制目标(上下缘=页首尾, 左右缘=所在行行首/尾)
                    // 才允许继续翻——否则页会跑在选区前面,选区与当前页不相交,
                    // 高亮手柄消失、工具栏孤立
                    val mDwell = selMetrics
                    val reachedEdge = selection?.let { sel ->
                        val bp = livePage
                        if (bp == null || bp.lines.isEmpty()) false
                        else when {
                            selDrag.pointerY > contentBottomY -> {
                                val last = bp.lines.last()
                                sel.endGlobal >= last.lineStartGlobal + last.text.length
                            }
                            selDrag.pointerX > dwellRight -> {
                                val m = mDwell ?: return@let false
                                val li = SelectionGeometry.locateLine(bp, selDrag.pointerY - contentTopPx, m)
                                    ?: return@let false
                                val ln = bp.lines[li]
                                sel.endGlobal >= ln.lineStartGlobal + ln.text.length
                            }
                            selDrag.pointerX < dwellLeft -> {
                                val m = mDwell ?: return@let false
                                val li = SelectionGeometry.locateLine(bp, selDrag.pointerY - contentTopPx, m)
                                    ?: return@let false
                                sel.startGlobal <= bp.lines[li].lineStartGlobal
                            }
                            else -> sel.startGlobal <= bp.lines.first().lineStartGlobal
                        }
                    } == true
                    if (!reachedEdge) continue
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
        selDrag.pointerX = px
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
        // cyEff 是窗口坐标,hit 期望版心坐标: 必须减 contentTopPx,否则命中点
        // 系统性偏下一行(contentTopPx≈一行高),一动锚点选区就翻转
        val hit = SelectionGeometry.hit(bp, px - t.marginPx, cyEff - contentTopPx, m)
        val hit2 = hit ?: return
        // 指针进入四向驻留带: 端点直接钳制到页首/页尾(上下缘)或所在行的行首/行尾
        // (左右缘)——否则端点只能到手指 x 所在的字符,永远到不了边界,驻留翻页门不放开
        val cxContent = px - t.marginPx
        if (py >= contentTopPx && py <= contentBottomY &&
            cxContent >= 0f && cxContent <= (viewport?.width ?: 0) - 2 * t.marginPx
        ) selDrag.sawContent = true
        val v = when {
            py < contentTopPx -> bp.lines.first().lineStartGlobal
            py > contentBottomY -> {
                val last = bp.lines.last()
                last.lineStartGlobal + last.text.length
            }
            cxContent < 0f -> bp.lines[hit2.first].lineStartGlobal
            cxContent > (viewport?.width ?: 0) - 2 * t.marginPx -> {
                val ln = bp.lines[hit2.first]
                ln.lineStartGlobal + ln.text.length
            }
            else -> SelectionGeometry.globalAt(bp, hit2.first, hit2.second)
        }
        val updated = sel.withAnchor(v)
        // 兜底: 一次只允许一个锚点在动——固定端被连带移动、或结果为空选区时,丢弃本次更新
        val frozenIntact = selDrag.frozen == null ||
            updated.startGlobal == selDrag.frozen || updated.endGlobal == selDrag.frozen
        if (updated.isEmpty() || !frozenIntact) return
        if (updated != sel) selection = updated
        val inBand = py < contentTopPx || py > contentBottomY ||
            cxContent < 0f || cxContent > (viewport?.width ?: 0) - 2 * t.marginPx
        selDrag.magnifier?.let {
            if (Build.VERSION.SDK_INT >= 29) {
                val mag = it as Magnifier
                if (inBand) mag.dismiss() else mag.show(px, py)
            }
        }
    }

    fun onHandleDragEnd() {
        selDragActive = false
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
                            // 角标点击优先于菜单开关(仅菜单收起时检测;弹层自身拦截后续触摸)
                            val note = if (!menuVisible) footnoteHitAt(offset) else null
                            if (note != null) footnoteShow = note else menuVisible = !menuVisible
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
                    if (decorativeOn) return null   // 装饰章滚动查看,拖拽翻页禁用
                    val sp = liveSpecs ?: return null
                    val t = liveTypo ?: return null
                    val cur = livePage ?: return null
                    val cnt = liveContent ?: return null
                    if (sp.isEmpty()) return null
                    val idx = livePageIndex.coerceIn(0, sp.lastIndex)
                    val target = if (dir > 0) idx + 1 else idx - 1
                    if (target !in sp.indices) return null
                    // 命中预物化缓存则零成本定向;未命中(理论上仅冷启动首拖)才同步兜底
                    val neighbor = neighborCache.getOrPut(target) {
                        BookPager.materialize(cnt, sp[target], t, target, sp.size)
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
                            if (!decorativeOn && !sameDirAsBack && shouldFlip) {
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
        if (decorativeOn) {
            // 装饰章: WebView 原书样式(夜间反色滤镜由组件按 night 注入)
            DecorativeChapterView(
                docUrl = decorativeDocUrl
                    ?: "https://book.local/",   // 反查未完成先空白页占位
                rootDir = java.io.File(book.cachePath),   // 解压根(OEBPS/META-INF 所在)
                night = bgColor.luminance() < 0.5f,
                modifier = Modifier.fillMaxSize()
            )
            // 翻章按钮: 装饰章内滚动查看,章节切换经由按钮(与正文点击翻页习惯衔接)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val ciNow = specs?.getOrNull(pageIndex)?.chapterIndex ?: 0
                if (ciNow > 0) {
                    TextButton(
                        onClick = {
                            val sp = specs ?: return@TextButton
                            val idx = sp.indexOfFirst { it.chapterIndex == ciNow - 1 }
                            if (idx >= 0) pageIndex = idx
                        }
                    ) { Text("上一章", color = fgColor) }
                }
                if (ciNow + 1 < book.chapters.size) {
                    TextButton(
                        onClick = {
                            val sp = specs ?: return@TextButton
                            val idx = sp.indexOfFirst { it.chapterIndex == ciNow + 1 }
                            if (idx >= 0) pageIndex = idx
                        }
                    ) { Text("下一章", color = fgColor) }
                }
            }
        } else {
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
        }

        // 选择手柄: 左右倾斜水滴挂在各自锚点下方(参考系统选区柄),
        // 共用一个触摸区,按指针在两锚点中点的左右判定拖哪个柄——
        // 单字选中时两柄贴近,分立热区会互相遮挡导致抓错,合并后按位置判定不会错
        // 拖动会话期间即使选区与当前页暂时不相交(跨页驻留翻页的一瞬间)也保持挂载,
        // 否则手柄被卸载会取消拖动手势,跨页扩展中断、选区滞留在页外
        val lastVisual = remember { mutableStateOf<SelectionGeometry.SelectionVisual?>(null) }
        selectionVisual?.let { lastVisual.value = it }
        if ((selectionVisual != null || selDragActive) && typo != null && !menuVisible) {
            val t = typo
            val sv = selectionVisual ?: lastVisual.value
            if (sv != null) {
                val handleColor = Color(0xFF26A69A)   // 水滴柄用固定强调色(青),深浅主题下都醒目
                SelectionHandles(
                startX = sv.startHandle.left + t.marginPx,
                endX = sv.endHandle.left + t.marginPx,
                startY = sv.startHandle.bottom + contentTopPx,
                endY = sv.endHandle.bottom + contentTopPx,
                color = handleColor,
                drawStart = !sv.extendsTop,
                drawEnd = !sv.extendsBottom,
                onDragStart = ::onHandleDragStart,
                onDrag = ::onHandleDrag,
                onDragEnd = ::onHandleDragEnd
                )
            }
        }

        // loading 延迟显示: 内容就绪通常只需几十 ms(分页缓存命中),spinner 闪一下反而晃眼;
        // 超过 350ms 未就绪(冷缓存整本重排/大文件)才出现
        val contentReady = content != null && specs != null && currentBookPage != null
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
                IconButton(onClick = { showBookInfo = true }) {
                    Icon(Icons.Rounded.Info, "书籍信息", tint = fgColor)
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
                // 跳页调试入口: 进度条左侧图标,点击弹数字跳页弹窗
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showJumpPage = true }) {
                        Icon(Icons.Rounded.Input, "跳页", tint = fgColor)
                    }
                    Slider(
                        modifier = Modifier.weight(1f),
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
                }
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
                showToc = false
                val sp = specs
                if (sp != null && idx in book.chapters.indices) {
                    // 目录锚点: 章带 fragment 时落锚点所在段落(章文档可能未缓存,后台读)
                    val anchorId = book.chapters[idx].anchorId
                    scope.launch {
                        val target = withContext(Dispatchers.Default) {
                            val byAnchor = if (anchorId != null) {
                                liveContent?.anchorOffset(idx, anchorId)?.let { off ->
                                    val global = (liveContent?.chapterStart(idx) ?: 0L) + off
                                    BookPager.locatePage(sp, global)
                                        .takeIf { sp[it].chapterIndex == idx }   // 防御: 异常落点退章首
                                }
                            } else null
                            byAnchor ?: sp.indexOfFirst { it.chapterIndex == idx }
                        }
                        if (target >= 0) pageIndex = target
                    }
                }
            },
            onDismiss = { showToc = false }
        )
    }

    // 跳页调试弹窗: 输入页码直达(与进度条/目录同一条页目录索引路径)
    if (showJumpPage) {
        val totalPages = specs?.size ?: 0
        var input by remember(showJumpPage) { mutableStateOf((pageIndex + 1).toString()) }
        val target = input.trim().toIntOrNull()
        AlertDialog(
            onDismissRequest = { showJumpPage = false },
            title = { Text("跳页") },
            text = {
                Column {
                    Text(
                        "当前第 ${pageIndex + 1} 页 / 共 $totalPages 页",
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
                            if (target != null && totalPages > 0) {
                                pageIndex = (target - 1).coerceIn(0, totalPages - 1)
                                showJumpPage = false
                            }
                        })
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = target != null && totalPages > 0,
                    onClick = {
                        pageIndex = ((target ?: 1) - 1).coerceIn(0, totalPages - 1)
                        showJumpPage = false
                    }
                ) { Text("跳转") }
            },
            dismissButton = {
                TextButton(onClick = { showJumpPage = false }) { Text("取消") }
            }
        )
    }

    if (showBookInfo) {
        ModalBottomSheet(onDismissRequest = { showBookInfo = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "书籍信息",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                val dateFmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }
                val sizeText = when {
                    book.fileSize >= 1 shl 20 -> "%.1f MB".format(book.fileSize / 1048576.0)
                    book.fileSize >= 1024 -> "${book.fileSize / 1024} KB"
                    else -> "${book.fileSize} B"
                }
                val curChapter = specs?.getOrNull(pageIndex)?.chapterIndex ?: 0
                InfoRow("书名", book.title)
                book.author?.let { InfoRow("作者", it) }
                InfoRow("格式", if (book.format == BookFormat.EPUB) "EPUB" else "TXT")
                InfoRow("文件大小", sizeText)
                InfoRow("章节", "${book.chapters.count { it.level == 0 }} 章 · ${book.chapters.count { it.level > 0 }} 小节")
                InfoRow("全书字数", "%,d".format(book.totalChars))
                InfoRow("当前进度", "${(percent * 100).roundToInt()}% · ${
                    book.chapters.getOrNull(curChapter)?.title ?: ""
                }")
                InfoRow("添加时间", dateFmt.format(java.util.Date(book.addedAt)))
                InfoRow("最近阅读", dateFmt.format(java.util.Date(book.lastReadAt)))
            }
        }
    }

    footnoteShow?.let { note ->
        ModalBottomSheet(onDismissRequest = { footnoteShow = null }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp).padding(bottom = 24.dp)
            ) {
                Text(
                    "脚注",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    note.second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
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
    content: BookContent?,
    specs: List<PageSpec>?,
    pageIndex: Int
): Double = content?.let { c ->
    specs?.getOrNull(pageIndex)?.let { BookPager.percentOf(c, it) }
} ?: 0.0


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
    drawStart: Boolean,
    drawEnd: Boolean,
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

    // 触摸盒随选区频繁跳位,而 onGloballyPositioned 的原点更新与指针事件异步——
    // 盒一跳,盒内坐标跟着突变,拼出的指针 y 瞬间窜动约一行,命中窜到邻行导致选区错误翻转。
    // 因此拖动会话期间冻结盒原点(按下时快照),坐标全程稳定;图标仍按实时锚点绘制。
    // 手势外的缓慢跟随不受影响(盒原点只快照不更新)
    val frozenTopLeft = remember { mutableStateOf<Offset?>(null) }
    val originRef = remember { mutableStateOf<Offset?>(null) }
    val liveStartX by rememberUpdatedState(startX)
    val liveEndX by rememberUpdatedState(endX)
    // 拖动端在首个事件判定一次并固定整个会话;null = 会话尚未判定
    var sessionFromStart by remember { mutableStateOf<Boolean?>(null) }
    val effLeft = frozenTopLeft.value?.x ?: left
    val effTop = frozenTopLeft.value?.y ?: top

    Box(
        modifier = Modifier
            .offset { IntOffset(effLeft.roundToInt(), effTop.roundToInt()) }
            .size(with(density) { (wPx / density.density).dp }, with(density) { (height / density.density).dp })
            .onGloballyPositioned { originRef.value = it.positionInWindow() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        frozenTopLeft.value = Offset(left, top)
                        sessionFromStart = null
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        originRef.value?.let { o ->
                            val px = o.x + change.position.x
                            val py = o.y + change.position.y
                            var fs = sessionFromStart
                            if (fs == null) {
                                fs = px < (liveStartX + liveEndX) / 2f
                                sessionFromStart = fs
                                onDragStart(fs)
                            }
                            onDrag(fs, px, py)
                        }
                    },
                    onDragEnd = { frozenTopLeft.value = null; onDragEnd() },
                    onDragCancel = { frozenTopLeft.value = null; onDragEnd() }
                )
            }
    ) {
        // 系统样式水滴矢量图: 尖端贴锚点;绕尖端向外倾斜 45°(左柄向左倒,右柄向右倒)
        val iconWTpx = with(density) { 28.dp.toPx() }
        val iconHTpx = with(density) { 36.dp.toPx() }
        fun handleModifier(anchorX: Float, relY: Float, tilt: Float): Modifier = Modifier
            .offset { IntOffset((anchorX - effLeft - iconWTpx / 2).roundToInt(), relY.roundToInt()) }
            .size(24.dp, 30.dp)
            .graphicsLayer {
                rotationZ = tilt
                transformOrigin = TransformOrigin(0.5f, 0f)   // 绕尖端旋转,尖端始终贴锚点
            }
        // 端点被钳制到页首/页尾(选区延续到邻页)时, 该柄不画——它不对应真实选区边界,
        // 画出来会像一个游离在页角的锚点; 高亮已铺满整页, 延续关系由整页高亮表达
        if (drawStart) Image(
            painter = painterResource(R.drawable.reader_handle_drop),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = handleModifier(startX, startY - effTop, 45f)
        )
        if (drawEnd) Image(
            painter = painterResource(R.drawable.reader_handle_drop),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = handleModifier(endX, endY - effTop, -45f)
        )
    }
}

// 书籍信息弹层的键值行
@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
