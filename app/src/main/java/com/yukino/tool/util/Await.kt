package com.yukino.tool.util

import com.google.android.gms.tasks.Task
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Guava ListenableFuture 挂起等待(如 CameraX 的 ProcessCameraProvider.getInstance)
suspend fun <T> ListenableFuture<T>.await(): T {
    return suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: CancellationException) {
                cont.cancel(e)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, { it.run() })
        cont.invokeOnCancellation { cancel(false) }
    }
}

// GMS Task 挂起等待(如 ML Kit 的 scanner.process)
suspend fun <T> Task<T>.await(): T {
    return suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(task.exception ?: RuntimeException("Task failed"))
            }
        }
    }
}
