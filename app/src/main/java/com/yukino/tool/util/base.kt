package com.yukino.tool.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

internal fun Context.findActivity(): Activity {
    val context = this;
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
    }
    throw IllegalStateException("No Activity found")
}

@Composable
fun currentActivity(): Activity {
    val currentContext = LocalContext.current
    return currentContext.findActivity()
}