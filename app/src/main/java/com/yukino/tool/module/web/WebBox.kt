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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yukino.tool.components.text
import com.yukino.tool.module.web.Web
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.suspendCoroutine

typealias WebBoxFunc = @Composable (
    initUrl: String,
    onNew: ((url: String) -> Unit)?,
    onShowList: (() -> Unit)?,
    webLength: Int,
    webIndex: Int,
    active: Boolean,
    onlyOpenSameSite: Boolean,
    onOnlyOpenSameSiteChange: (Boolean) -> Unit
) -> Unit

@SuppressLint("SetJavaScriptEnabled")
val WebBox: WebBoxFunc = { initUrl, onNew, onShowList, webLength, webIndex, active, onlyOpenSameSite, onOnlyOpenSameSiteChange ->

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
                .background(MaterialTheme.colorScheme.primary)
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
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
        ) {
            Web(
                initUrl = url,
                onNew = onNew,
                active = active,
                onUrlChange = { url = it ?: "" },
                onProgressChange = {
                    progress = it
                },
                onIconChange = { icon = it },
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
                .background(MaterialTheme.colorScheme.primary)
                .height(48.dp)
                .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 3.dp)
            ,
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            //点击地址文字弹出长输入框，确认后直接访问
            Text(
                text = url,
                color = Color.White,
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
                tint = Color.White
            )
            Text(
                modifier = Modifier.clickable {
                    onShowList?.invoke()
                }.border(
                    border = BorderStroke(2.dp, Color.White),
                    shape = RoundedCornerShape(2.dp)
                ).padding(horizontal = 5.dp, vertical = 0.dp),
                text = "${webIndex + 1}/${webLength}",
                color = Color.White,
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
                    tint = Color.White
                )
                DropdownMenu(
                    expanded = settingExpended,
                    onDismissRequest = { settingExpended = false }
                ) {
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
                title = { Text(text = "访问网址") },
                text = {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        //多行软换行,长地址尽量完整显示
                        maxLines = 5,
                        placeholder = { Text(text = "输入网址或搜索内容") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigate(urlInput) })
                    )
                },
                confirmButton = {
                    TextButton(onClick = { navigate(urlInput) }) { Text(text = "访问") }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlEdit = false }) { Text(text = "取消") }
                }
            )
        }
    }
}