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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yukino.tool.components.text
import com.yukino.tool.module.web.Web

typealias WebBoxFunc = @Composable (
    initUrl: String,
    onNew: ((url: String) -> Unit)?,
    onShowList: (() -> Unit)?,
    webLength: Int,
    webIndex: Int,
    enableBack: Boolean
) -> Unit

@SuppressLint("SetJavaScriptEnabled")
val WebBox: WebBoxFunc = { initUrl, onNew, onShowList, webLength, webIndex, enableBack ->

    var url by rememberSaveable {
        mutableStateOf(initUrl)
    }

    var enableJump by remember {
        mutableStateOf(true)
    }

    //加载进度
    var progress by remember {
        mutableFloatStateOf(0f)
    }

    val urlState = rememberTextFieldState(initialText = url)

    var title by remember {
        mutableStateOf<String?>(null)
    }

    var icon by remember {
        mutableStateOf<Bitmap?>(null)
    }

    var currentIndex by remember {
        mutableStateOf(0)
    }

    var historyCount by remember {
        mutableStateOf(1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                    text = title.text("无标题"),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = "${currentIndex + 1}/${historyCount}",
                color = Color.White
            )
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
                onUrlChange = {
                    urlState.edit {
                        this.replace(0, this.length, it)
                    }
                    url = it
                },
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
                webIndex = webIndex,
                enableBack = enableBack,
                onHistory = { webview, url, isReload ->
                    currentIndex = webview.copyBackForwardList().currentIndex
                    historyCount = webview.copyBackForwardList().size
                }
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
            val focusRequest = remember {
                FocusRequester()
            }
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            var showInput by remember {
                mutableStateOf(false)
            }
            BasicTextField(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .focusRequester(focusRequest)
                    .onFocusChanged {
                        showInput = it.isFocused
                    },
                state = urlState,
                lineLimits = TextFieldLineLimits.SingleLine,
                decorator = { innerTextField ->
                    if (showInput) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(40.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 15.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    innerTextField()
                                }
                                if (urlState.text.isNotEmpty()) {
                                    Icon(
                                        modifier = Modifier
                                            .padding(4.dp)
                                            .clickable {
                                                urlState.edit {
                                                    this.delete(0, this.length)
                                                }
                                                focusRequest.requestFocus()
                                            },
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清空"
                                    )
                                }
                            }

                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = url,
                                color = Color.White,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = {
                    val text = urlState.text.toString()
                    url = if (
                        text.startsWith("http://")
                        || text.startsWith("https://")) {
                        text
                    } else {
                        "https://www.google.com/search?q=${text}"
                    }
                    focusManager.clearFocus()
                    keyboardController?.hide()
//                    showInput = false
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
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "设置",
                tint = Color.White
            )
//            Switch(
//                checked = enableJump,
//                onCheckedChange = {
//                    enableJump = it
//                },
//                colors = SwitchDefaults.colors(
//                    checkedTrackColor = MaterialTheme.colorScheme.background,
//                    checkedThumbColor = MaterialTheme.colorScheme.primary,
//                    uncheckedBorderColor = Color.Transparent,
//                    uncheckedThumbColor = MaterialTheme.colorScheme.onTertiary,
//                    uncheckedTrackColor = Color.LightGray
//                )
//            )
        }
    }
}