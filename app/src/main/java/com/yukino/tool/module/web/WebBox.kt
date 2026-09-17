import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import com.yukino.tool.components.text
import com.yukino.tool.module.web.CustomWebView
import com.yukino.tool.module.web.Web
import com.yukino.tool.module.web.WebHistoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.coroutines.suspendCoroutine

typealias WebBoxFunc = @Composable (
    initUrl: String,
    onNew: ((url: String) -> Unit)?,
    onShowList: (() -> Unit)?,
    webLength: Int,
    webIndex: Int,
    active: Boolean,
    onlyOpenSameSite: Boolean,
    onOnlyOpenSameSiteChange: (Boolean) -> Unit,
    onBoxBack: () -> Unit,
    onHistory: (webview: CustomWebView, url: String?, isReload: Boolean) -> Unit,
    //favicon回调: url+图标交给浏览器层回填浏览历史
    onHistoryIcon: (url: String, icon: Bitmap) -> Unit,
    onCloseBox: () -> Unit,
    onWebViewReady: (CustomWebView) -> Unit
) -> Unit

@SuppressLint("SetJavaScriptEnabled")
val WebBox: WebBoxFunc = { initUrl, onNew, onShowList, webLength, webIndex, active, onlyOpenSameSite, onOnlyOpenSameSiteChange, onBoxBack, onHistory, onHistoryIcon, onCloseBox, onWebViewReady ->

    val scope = rememberCoroutineScope()

    var url by rememberSaveable {
        mutableStateOf(initUrl)
    }

    //加载进度
    var progress by remember {
        mutableFloatStateOf(0f)
    }

    var title by remember {
        mutableStateOf<String?>(null)
    }

    var icon by remember {
        mutableStateOf<Bitmap?>(null)
    }

    //长输入框: 点击底栏地址文字弹出,确认后直接访问
    var showUrlEdit by remember {
        mutableStateOf(false)
    }
    var urlInput by remember {
        mutableStateOf("")
    }

    //浏览历史: 持久化存储, 设置菜单里查看/清空
    val historyContext = LocalContext.current
    var historyItems by remember {
        mutableStateOf(WebHistoryStore.load(historyContext))
    }
    var showHistory by remember {
        mutableStateOf(false)
    }

    var navUrl by remember {
        mutableStateOf<String?>(null)
    }
    var navKey by remember {
        mutableStateOf(0)
    }

    fun navigate(target: String) {
        url = if (target.startsWith("http://") || target.startsWith("https://")) {
            target
        } else {
            "https://www.google.com/search?q=$target"
        }
        navUrl = url
        navKey++   // 触发 Web 组件加载
        showUrlEdit = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEEEEEE))
                .then(if (active) Modifier.statusBarsPadding() else Modifier)
                .height(32.dp)
                .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 3.dp)
            ,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                icon?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "图表"
                    )
                }
                Text(
                    text = if (title == null && progress < 1f) "加载中..." else title.text("无标题"),
                    color = Color(0xFF333333),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
        ) {
            //加载进度条: 叠加在网页内容顶部, 加载完成后完全消失, 无布局占位
            if (progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .zIndex(1f),
                    color = Color(0xFF6B6B6B),
                    trackColor = Color.Transparent
                )
            }
            Web(
                initUrl = url,
                active = active,
                onNew = onNew,
                onBoxBack = onBoxBack,
                onHistory = onHistory,
                onWebViewReady = onWebViewReady,
                onUrlChange = { url = it ?: "" },
                onProgressChange = {
                    progress = it
                },
                onIconChange = {
                    icon = it
                    //favicon到货: 回传浏览器层回填对应历史条目
                    val bmp = it
                    if (bmp != null && url.isNotBlank()) {
                        onHistoryIcon(url, bmp)
                    }
                },
                onTitleChange = { title = it },
                onSelected = { selectedText, _ ->
                    val openUrl = if (selectedText.startsWith("http://") || selectedText.startsWith("https://")) {
                        selectedText
                    } else {
                        "https://www.google.com/search?q=${selectedText}"
                    }
                    onNew?.invoke(openUrl)
                },
                onlyOpenSameSite = onlyOpenSameSite,
                navigateUrl = navUrl,
                navigateKey = navKey
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEEEEEE))
                .navigationBarsPadding()
                .height(48.dp)
                .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 3.dp)
            ,
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            //点击地址文字弹出长输入框，确认后直接访问
            Text(
                text = url,
                color = Color(0xFF333333),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        urlInput = url
                        showUrlEdit = true
                    }
            )
            Icon(
                modifier = Modifier.clickable {
                    if (onNew != null) {
                        onNew("https://www.google.com/ncr")
                    }
                },
                imageVector = Icons.Default.Add,
                contentDescription = "新标签",
                tint = Color(0xFF333333)
            )
            Text(
                modifier = Modifier.clickable {
                    onShowList?.invoke()
                }.border(
                    border = BorderStroke(1.dp, Color(0xFF333333)),
                    shape = RoundedCornerShape(2.dp)
                ).padding(horizontal = 5.dp, vertical = 0.dp),
                text = "${webIndex + 1}/${webLength}",
                color = Color(0xFF333333),
                fontSize = 14.sp
            )
            Box {
                var settingExpended by remember {
                    mutableStateOf(false)
                }
                Icon(
                    modifier = Modifier.clickable {
                        settingExpended = !settingExpended
                    },
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = Color(0xFF333333)
                )
                DropdownMenu(
                    expanded = settingExpended,
                    onDismissRequest = { settingExpended = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("浏览历史") },
                        onClick = {
                            settingExpended = false
                            historyItems = WebHistoryStore.load(historyContext)
                            showHistory = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("只允许打开同站地址") },
                        onClick = {
                            settingExpended = false
                        },
                        trailingIcon = {
                            Switch(
                                checked = onlyOpenSameSite,
                                onCheckedChange = {
                                    onOnlyOpenSameSiteChange(it)
                                    scope.launch {
                                        delay(200)
                                        settingExpended = false
                                    }

                                },
                                modifier = Modifier.scale(0.8f),
                            )
                        }
                    )
                }
            }
        }

        if (showUrlEdit) {
            AlertDialog(
                onDismissRequest = { showUrlEdit = false },
                //不受平台默认宽度限制,弹框近乎全屏宽,输入框尽量显示全地址
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                title = { Text(text = "访问网址") },
                text = {
                    Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        //多行软换行,长地址尽量完整显示
                        maxLines = 20,
                        placeholder = { Text(text = "输入网址或搜索内容") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigate(urlInput) })
                    )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { navigate(urlInput) }) { Text(text = "访问") }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlEdit = false }) { Text(text = "取消") }
                }
            )
        }

        //浏览历史弹窗: 持久化记录, 点击条目新开webview打开
        if (showHistory) {
            AlertDialog(
                onDismissRequest = { showHistory = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "浏览历史(${historyItems.size})", modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            historyItems.clear()
                            WebHistoryStore.clear(historyContext)
                        }) { Text(text = "清空") }
                    }
                },
                text = {
                    if (historyItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "暂无历史记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(items = historyItems, key = { _, item -> item.url + item.time }) { _, item ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onNew?.invoke(item.url)
                                            showHistory = false
                                        }
                                ) {
                                    val bmp = item.iconBitmap()
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    } else {
                                        Text(text = "🌐", fontSize = 24.sp)
                                    }
                                    Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(
                                            text = item.title.ifBlank { item.url },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.url,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = SimpleDateFormat("MM-dd HH:mm").format(Date(item.time)),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHistory = false }) { Text(text = "关闭") }
                }
            )
        }
    }
}