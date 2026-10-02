package com.yukino.tool.module.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yukino.tool.module.reader.common.ImportResult
import com.yukino.tool.module.reader.common.Progress
import com.yukino.tool.module.reader.common.ReaderBook
import com.yukino.tool.module.reader.common.ReaderGroup
import com.yukino.tool.module.reader.common.ReaderSettings
import com.yukino.tool.ui.theme.ToolTheme
import com.yukino.tool.util.FilePicker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Reader"

class ReaderActivity : ComponentActivity() {

    private lateinit var readerFilePicker: FilePicker

    // 外部"打开方式/分享"进来的文档 uri,由 ReaderApp 消费后置空(防重建后重复导入)
    private val incomingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边: 内容延伸到状态栏/导航栏后面,系统栏透明悬浮(各页面自行 inset 避让)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // FilePicker 构造时注册 launcher,必须早于 STARTED(与 CompressActivity 同约定)
        readerFilePicker = FilePicker(this)
        incomingUri.value = extractImportUri(intent)
        setContent {
            ToolTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReaderApp(
                        filePicker = readerFilePicker,
                        incomingUri = incomingUri.value,
                        onIncomingUriConsumed = { incomingUri.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUri.value = extractImportUri(intent)
    }

    // ACTION_VIEW(打开方式)取 data,ACTION_SEND(分享)取 EXTRA_STREAM
    private fun extractImportUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }
}

// 模块内导航: 书架(分组层级) ↔ 阅读页(状态切换,不进 nav 图)
@Composable
fun ReaderApp(
    filePicker: FilePicker,
    incomingUri: Uri?,
    onIncomingUriConsumed: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var books by remember { mutableStateOf<List<ReaderBook>>(emptyList()) }
    var groups by remember { mutableStateOf<List<ReaderGroup>>(emptyList()) }
    // 当前所在的分组层级: null = 书架顶层(未分组书籍所在层)
    var currentGroupId by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(ReaderSettings()) }
    var loaded by remember { mutableStateOf(false) }
    var readingId by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        books = ReaderStore.loadBooks(context)
        groups = ReaderStore.loadGroups(context)
    }

    LaunchedEffect(Unit) { refresh(); settings = ReaderStore.loadSettings(context); loaded = true }

    // 单行落库: 进度/懒初始化回填都只写自己的行, 不再全量重写
    val updateBook: (String, (ReaderBook) -> ReaderBook) -> Unit = { id, transform ->
        val next = books.map { if (it.id == id) transform(it) else it }
        books = next
        next.firstOrNull { it.id == id }?.let {
            scope.launch(Dispatchers.IO) { ReaderStore.upsertBook(context, it) }
        }
    }

    // 退出/切后台只落设置(书籍写已是单行即时);回前台重载书目——
    // 分享/首页会各自创建 Activity 实例, 不刷新则多个入口看到的列表会分叉
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> ReaderStore.saveSettings(context, settings)
                Lifecycle.Event.ON_START -> scope.launch(Dispatchers.IO) {
                    val fresh = ReaderStore.loadBooks(context)
                    if (readingId == null) books = fresh   // 阅读中不打断当前书
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 导入文件: 登记 → 自动打开(懒初始化在阅读页 loading 内完成)
    fun importFile() {
        scope.launch {
            val uri = filePicker.open(arrayOf("text/*", "application/octet-stream", "application/epub+zip"))
                ?: return@launch
            importing = true
            val result = BookRegistrar.register(context, uri, currentGroupId)
            importing = false
            when (result) {
                is ImportResult.Success -> {
                    refresh()
                    readingId = result.book.id
                }
                is ImportResult.FolderImported -> {}
                is ImportResult.Failed -> Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 导入文件夹: 目录结构 → 分组结构, 登记后留在书架(书为未解析态, 打开时初始化)
    fun importFolder() {
        scope.launch {
            val uri = filePicker.openFolder() ?: return@launch
            importing = true
            val result = BookRegistrar.registerFolder(context, uri, currentGroupId)
            importing = false
            when (result) {
                is ImportResult.FolderImported -> {
                    refresh()
                    val msg = buildString {
                        if (result.groupCount > 0) append("已导入 ${result.groupCount} 个分组 · ")
                        append("${result.bookCount} 本书")
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                is ImportResult.Success -> {}
                is ImportResult.Failed -> Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 外部"打开方式/分享"导入: 登记 + 自动打开
    fun importFromUri(uri: Uri) {
        scope.launch {
            importing = true
            val result = BookRegistrar.register(context, uri, currentGroupId)
            importing = false
            when (result) {
                is ImportResult.Success -> {
                    refresh()
                    readingId = result.book.id
                }
                is ImportResult.FolderImported -> {}
                is ImportResult.Failed -> Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 书目加载完成且带外部文档 uri 时触发;先消费 uri 再异步导入(导入协程不随 key 重启)
    LaunchedEffect(incomingUri, loaded) {
        val uri = incomingUri ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        onIncomingUriConsumed()
        importFromUri(uri)
    }

    fun deleteBook(book: ReaderBook) {
        books = books.filterNot { it.id == book.id }
        scope.launch(Dispatchers.IO) {
            File(book.cachePath).deleteRecursively()   // txt 缓存文件 / epub 章节目录
            ReaderStore.deleteSpecs(context, book.id)
            ReaderStore.deleteBookRow(context, book.id)
        }
    }

    // 删组及全部内容: 递归删子组与组内书籍(缓存文件+库行, specs 级联), 不可恢复
    fun deleteGroupAll(group: ReaderGroup) {
        val ids = mutableListOf(group.id)
        var frontier = listOf(group.id)
        while (frontier.isNotEmpty()) {
            frontier = groups.filter { it.parentId in frontier }.map { it.id }
            ids += frontier
        }
        scope.launch(Dispatchers.IO) {
            val deleted = ReaderStore.deleteGroupsDeep(context, ids)
            deleted.forEach { File(it.cachePath).deleteRecursively() }
        }
        groups = groups.filterNot { it.id in ids }
        val removed = books.filter { it.groupId in ids }
        books = books.filterNot { it.groupId in ids }
        if (removed.isNotEmpty()) {
            Toast.makeText(
                context, "已删除「${group.name}」及 ${removed.size} 本书", Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 删组: 子组递归收集一并删除, 组内书籍(各层)回归未分组;书本身不动
    fun deleteGroup(group: ReaderGroup) {
        val ids = mutableListOf(group.id)
        var frontier = listOf(group.id)
        while (frontier.isNotEmpty()) {
            frontier = groups.filter { it.parentId in frontier }.map { it.id }
            ids += frontier
        }
        groups = groups.filterNot { it.id in ids }
        books = books.map { if (it.groupId in ids) it.copy(groupId = null) else it }
        scope.launch(Dispatchers.IO) { ReaderStore.deleteGroups(context, ids) }
    }

    // 组内系统返回键回上一层分组(阅读态由阅读页自己的 BackHandler 接管)
    BackHandler(enabled = readingId == null && currentGroupId != null) {
        currentGroupId = groups.firstOrNull { it.id == currentGroupId }?.parentId
    }

    val readingBook = readingId?.let { id -> books.firstOrNull { it.id == id } }

    if (!loaded) {
        return
    }

    val current = readingBook
    if (current != null) {
        ReaderScreen(
            book = current,
            settings = settings,
            onSettingsChange = { next ->
                settings = next
                scope.launch(Dispatchers.IO) { ReaderStore.saveSettings(context, next) }
            },
            onProgress = { globalCharOffset, percent ->
                updateBook(current.id) {
                    it.copy(
                        progress = Progress(globalCharOffset = globalCharOffset, percent = percent),
                        lastReadAt = System.currentTimeMillis()
                    )
                }
            },
            onInitComplete = { refreshed ->
                // 懒初始化回填(ready/chapters/书名)同步进内存态;行已在库, 仅更新本地
                books = books.map { if (it.id == refreshed.id) refreshed else it }
            },
            onBack = {
                readingId = null   // 进度已是单行即时落盘, 返回无需整表写回
            }
        )
    } else {
        val currentGroup = currentGroupId?.let { id -> groups.firstOrNull { it.id == id } }
        ReaderBookshelfPage(
            groups = groups.filter { it.parentId == currentGroupId },
            books = books.filter { it.groupId == currentGroupId },
            currentGroup = currentGroup,
            importing = importing,
            onOpen = { book ->
                updateBook(book.id) { it.copy(lastReadAt = System.currentTimeMillis()) }
                readingId = book.id
            },
            onDelete = { deleteBook(it) },
            onOpenGroup = { currentGroupId = it },
            onBackToParent = { currentGroupId = currentGroup?.parentId },
            onDeleteGroup = { deleteGroup(it) },
            onDeleteGroupAll = { deleteGroupAll(it) },
            onImportFile = { importFile() },
            onImportFolder = { importFolder() }
        )
    }
}
