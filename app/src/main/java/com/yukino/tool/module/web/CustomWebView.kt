package com.yukino.tool.module.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import com.yukino.tool.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs

interface ScrollListener {
    suspend fun onPullRefresh() {}
}

class CustomWebView(context: Context) : WebView(context) {

    var scrollListener: ScrollListener? = null

    var onCreateSelectorActionMode: ((mode: ActionMode?, menu: Menu?, callback: ActionMode.Callback?) -> Unit)? = null

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
                    if (isPull == true && tempDownY != null && tempDownY < (this@CustomWebView.bottom - this@CustomWebView.top) * 0.7 && upY - tempDownY > 500) {
                        if (isTop && !isRefreshing) {
                            isRefreshing = true
                            scrollListener?.onPullRefresh()
                        }
                    }
                } finally {
                    this@CustomWebView.resetPullRefreshState()
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

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode {
        Log.i(TAG, "startActionMode: 2, ${type}")
        val self = this;
        val actionMode = super.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                self.onCreateSelectorActionMode?.invoke(mode, menu, callback)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                return callback!!.onPrepareActionMode(mode, menu)
            }

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                return callback!!.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                return callback!!.onDestroyActionMode(mode)
            }

            override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect?) {
                (callback as ActionMode.Callback2).onGetContentRect(mode, view, outRect)
            }

        }, type)
        return actionMode
    }

    suspend fun getSelectedText(): String {
        return suspendCoroutine {
            this.evaluateJavascript("window.getSelection().toString()") { result ->
                it.resume(if (result.isNotEmpty()) result.substring(1, result.length - 1) else result)
            }
        }
    }

}