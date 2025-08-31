package com.yukino.tool.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yukino.tool.TAG
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.suspendCoroutine

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
fun rememberCurrentActivity(): Activity {
    val currentContext = LocalContext.current
    return currentContext.findActivity()
}

@Composable
fun OnTimer(delay: Long = 500, block: suspend () -> Unit) {
    val currentCoroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(delay, effect = {
        var isHide = false
        currentCoroutineScope.launch {
            while (true) {
                if (!isHide) {
                    block()
                }
                delay(delay)
            }
        }
        val lifecycleObserver = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                isHide = false
            }

            override fun onPause(owner: LifecycleOwner) {
                isHide = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            Log.i(TAG, "onDispose")
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    })
}

class ValueRef<T>(var value: T)

class FilePicker(activity: ComponentActivity) {
    private var filePickerLauncher: ActivityResultLauncher<Array<String>> = registerLauncher(activity)

    private var onFilePicker: ((uri: Uri?) -> Unit)? = null

    private fun registerLauncher(activity: ComponentActivity): ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            onFilePicker?.invoke(it)
            onFilePicker = null
        }
    }

    suspend fun open(mimeTypes: Array<String>): Uri? {
        return suspendCoroutine {
            onFilePicker = { uri ->
                it.resumeWith(Result.success(uri))
            }
            filePickerLauncher.launch(mimeTypes)
        }
    }

}

class IntentLauncher(val activity: ComponentActivity) {

    private var intentLauncher: ActivityResultLauncher<Intent> = registerLauncher(activity)

    private var callback: ((activityResult: ActivityResult?) -> Unit)? = null

    private fun registerLauncher(activity: ComponentActivity): ActivityResultLauncher<Intent> {
        return activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            callback?.invoke(it)
            callback = null
        }
    }

    suspend fun send(intent: Intent): ActivityResult? {
        return suspendCoroutine {
            callback = { activityResult ->
                Log.i(TAG, "send: ${activityResult}")
                it.resumeWith(Result.success(activityResult))
            }
            intentLauncher.launch(intent)
        }
    }
}

class PermissionRequester(val activity: ComponentActivity) {

    private var callback: ((isGranted: Boolean) -> Unit)? = null

    private val requestPermissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            callback?.invoke(isGranted)
        }

    suspend fun request(permission: String): Boolean {
        return suspendCoroutine {
            if (activity.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                it.resumeWith(Result.success(true))
                return@suspendCoroutine
            }
            callback = { isGranted ->
                it.resumeWith(Result.success(isGranted))
            }
            requestPermissionLauncher.launch(permission)
        }
    }
}
