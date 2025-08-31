package com.yukino.tool.module.web

import WebBox
import WebBoxFunc
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.yukino.tool.TAG
import com.yukino.tool.util.rememberCurrentActivity
import kotlinx.coroutines.launch
import java.util.UUID

class WebWrapper(

    val key: String = UUID.randomUUID().toString(),

    val initUrl: String,

    val content: WebBoxFunc,

    var offsetY: Float = 0F,

    var prev: WebWrapper? = null,

    var next: WebWrapper? = null,

    var minOffsetY: Float = 0F,

    var maxOffsetY: Float = 0F,
) {

}

@Composable
fun WebBrowser() {

    val webBoxes = remember {
        mutableStateListOf<WebWrapper>()
    }

    var showList by remember {
        mutableStateOf(false)
    }

    val historyStack = remember {
        mutableListOf<WebWrapper>()
    }

    var showWebWrapper by remember {
        mutableStateOf<WebWrapper?>(null)
    }

    val currentActivity = rememberCurrentActivity() as ComponentActivity

    val onBackPressedCallback = remember {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                historyStack.removeAt(historyStack.lastIndex)
                if (historyStack.isNotEmpty()) {
                    showWebWrapper = historyStack.last()
                }
                this.isEnabled = !showList && historyStack.size > 1
                Log.i(TAG, "browser 回退: ${this.isEnabled}")
            }
        }
    }
    val minOffset = 800f

    LaunchedEffect(showWebWrapper) {
        val webWrapper = showWebWrapper
        if (webWrapper != null) {
            historyStack.remove(webWrapper)
            historyStack.add(historyStack.size, webWrapper)
            onBackPressedCallback.isEnabled = !showList && historyStack.size > 1
        }
    }

    DisposableEffect(Unit) {
        currentActivity.onBackPressedDispatcher.addCallback(onBackPressedCallback)
        onDispose {
            onBackPressedCallback.remove()
        }
    }

    fun getOffsetY(x: Float, b: Float): Float {
        return x + b
    }

    fun fillMaxMinOffsetY(webBoxes: List<WebWrapper>) {
        val gap = 500f
        val maxOffset = 500f
        val size = webBoxes.size
        webBoxes.forEachIndexed { index, webWrapper ->
            webWrapper.minOffsetY = getOffsetY(index * gap, - ((size - 1) * gap) + minOffset)
            webWrapper.maxOffsetY = getOffsetY(index * gap, maxOffset)
            webWrapper.offsetY = webWrapper.minOffsetY
            Log.i(TAG, "fillMaxMinOffsetY: ${webWrapper.minOffsetY}, ${webWrapper.maxOffsetY}, ${webWrapper.offsetY}")
        }
    }

    fun addWebBox(url: String) {
        val webWrapper = WebWrapper(
            initUrl = url,
            content = WebBox
        )
        if (webBoxes.isNotEmpty()) {
            webWrapper.prev = webBoxes.last()
            webBoxes.last().next = webWrapper
        }
        webBoxes.add(webWrapper)
        showWebWrapper = webWrapper
    }

    LaunchedEffect(Unit) {
        addWebBox("https://www.google.com/ncr")
        fillMaxMinOffsetY(webBoxes)
    }

    val recomposeScope = currentRecomposeScope

    val boxRect = remember {
        mutableStateOf(Rect.Zero)
    }

    val scrollableState = rememberScrollableState { delta ->
        webBoxes.forEach {
            it.offsetY += delta
            if (it.offsetY < it.minOffsetY) {
                it.offsetY = it.minOffsetY
            }
            if (it.offsetY > it.maxOffsetY) {
                it.offsetY = it.maxOffsetY
            }
        }
        recomposeScope.invalidate()
        delta
    }

    val currentCoroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .onGloballyPositioned {
                boxRect.value = it.boundsInParent()
            }
            .scrollable(
                state = scrollableState,
                enabled = showList,
                orientation = Orientation.Vertical
            )
    ) {
        webBoxes.forEachIndexed { index, it ->
            key(it.key) {
                val zIndex = if (!showList && it == showWebWrapper) webBoxes.size else index
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(
                                x = 0,
                                y = if (!showList) 0 else if (it.offsetY.toInt() > 0) it.offsetY.toInt() else 0
                            )
                        }
                        .zIndex(zIndex.toFloat())
                ) {
                    it.content(
                        it.initUrl,
                        {
                            addWebBox(it)
                            fillMaxMinOffsetY(webBoxes)
                        },
                        {
                            showList = true
                            Log.i(TAG, "WebBrowser: ${showList}, ${showWebWrapper?.offsetY}")
                            currentCoroutineScope.launch {
                                scrollableState.scrollBy(minOffset - (showWebWrapper?.offsetY ?: 0f))
                            }
                        },
                        webBoxes.size,
                        index,
                        !showList && showWebWrapper == it
                    )
                    if (showList) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color = Color.Transparent)
                                .clickable {
                                    showList = false
                                    showWebWrapper = it
                                }
                        ) {}
                    }
                }
            }
        }
    }
}