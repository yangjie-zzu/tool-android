package com.yukino.tool.module.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.yukino.tool.ui.theme.ToolTheme
import com.yukino.tool.util.FilePicker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

// 模块内导航: 书架 ↔ 阅读页(状态切换,不进 nav 图)
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
    var settings by remember { mutableStateOf(ReaderSettings()) }
    var loaded by remember { mutableStateOf(false) }
    var readingId by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    // 书目/进度变更计数: 变更后防抖 500ms 落盘
    val booksVersion = remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            books = ReaderStore.loadBooks(context)
            settings = ReaderStore.loadSettings(context)
        }
        loaded = true
    }

    fun saveBooksNow(list: List<ReaderBook>) {
        scope.launch(Dispatchers.IO) { ReaderStore.saveBooks(context, list) }
    }

    // 进度防抖落盘
    LaunchedEffect(booksVersion.intValue) {
        if (booksVersion.intValue == 0) return@LaunchedEffect
        delay(500)
        withContext(Dispatchers.IO) { ReaderStore.saveBooks(context, books) }
    }

    // 退出/切后台强制落盘;回前台重载书目——分享/首页会各自创建 Activity 实例,
    // 内存态 books 只在创建时加载,不刷新则两个入口看到的列表会分叉,
    // 且旧实例 ON_PAUSE 会把过期列表整体写回 books.json,抹掉另一实例新导入的书
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    ReaderStore.saveBooks(context, books)
                    ReaderStore.saveSettings(context, settings)
                }
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

    val updateBook: (String, (ReaderBook) -> ReaderBook) -> Unit = { id, transform ->
        books = books.map { if (it.id == id) transform(it) else it }
        booksVersion.intValue++
    }

    fun importBook() {
        scope.launch {
            val uri = filePicker.open(arrayOf("text/*", "application/octet-stream")) ?: return@launch
            importing = true
            val result = TxtImporter.import(context, uri)
            importing = false
            when (result) {
                is TxtImporter.ImportResult.Success -> {
                    books = ReaderStore.loadBooks(context)
                    booksVersion.intValue++
                }
                is TxtImporter.ImportResult.Failed -> Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 外部"打开方式/分享"导入: 直接从 uri 导入并自动打开这本书
    fun importFromUri(uri: Uri) {
        scope.launch {
            importing = true
            val result = TxtImporter.import(context, uri)
            importing = false
            when (result) {
                is TxtImporter.ImportResult.Success -> {
                    books = ReaderStore.loadBooks(context)
                    booksVersion.intValue++
                    readingId = result.book.id
                }
                is TxtImporter.ImportResult.Failed -> Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
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
        booksVersion.intValue++
        scope.launch(Dispatchers.IO) {
            File(book.cachePath).delete()
            ReaderStore.deleteSpecs(context, book.id)
            ReaderStore.saveBooks(context, books)
        }
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
            onBack = {
                // 返回书架前立即落盘进度
                saveBooksNow(books)
                readingId = null
            }
        )
    } else {
        ReaderBookshelfPage(
            books = books,
            importing = importing,
            onOpen = { book ->
                updateBook(book.id) { it.copy(lastReadAt = System.currentTimeMillis()) }
                readingId = book.id
            },
            onDelete = { deleteBook(it) },
            onImport = { importBook() }
        )
    }
}
