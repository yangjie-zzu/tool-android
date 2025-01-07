package com.yukino.tool.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import com.yukino.tool.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

interface ScrollListener {
    suspend fun onPullRefresh() {}
}

class MyWebView(context: Context) : WebView(context) {

    var scrollListener: ScrollListener? = null

    private var isTop = true

    private var isRefreshing = false
    
    private var downY: Float? = null

    private var isPull: Boolean? = null

    override fun onOverScrolled(sX: Int, sY: Int, clampedX: Boolean, clampedY: Boolean) {
//        Log.i(TAG, "onOverScrolled: sX: $sX, sY: $sY, clampedX: $clampedX, clampedY: $clampedY")
//        Log.i(TAG, "canScrollVertically 1: ${this.canScrollVertically(1)}")
//        Log.i(TAG, "canScrollVertically -1: ${this.canScrollVertically(-1)}")
        isTop = sY == 0 && clampedY
        return super.onOverScrolled(sX, sY, clampedX, clampedY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            downY = event.y
        }
        if (event?.action == MotionEvent.ACTION_MOVE) {
            if (isPull == null) {
                val y = event.y
                downY?.let {
                    if (abs(y - it) > ViewConfiguration.get(context).scaledTouchSlop) {
                        isPull = y > it
                    }
                }
            }
        }
        if (event?.action == MotionEvent.ACTION_UP) {
//            Log.i(TAG, "onTouchEvent: isTop: $isTop, isRefreshing: $isRefreshing, downY: $downY, y: ${event.y}, isPull: $isPull")
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val tempDownY = downY
                    val upY = event.y
                    if (isPull == true && tempDownY != null && tempDownY < (this@MyWebView.bottom - this@MyWebView.top) * 0.7 && upY - tempDownY > 500) {
                        if (isTop && !isRefreshing) {
                            isRefreshing = true
                            scrollListener?.onPullRefresh()
                        }
                    }
                } finally {
                    this@MyWebView.resetPullRefreshState()
                }
            }

        }
        return super.onTouchEvent(event)
    }

    private fun resetPullRefreshState() {
        isTop = true
        isRefreshing = false
        downY = null
        isPull = null
    }

}