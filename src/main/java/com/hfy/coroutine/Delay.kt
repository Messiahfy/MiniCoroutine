package com.hfy.coroutine

import com.hfy.coroutine.cancel.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private val executor = Executors.newScheduledThreadPool(1) { runnable ->
    Thread(runnable, "Scheduler").apply { isDaemon = true }
}

/**
 * 支持取消的delay实现
 */
suspend fun delay(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS) {
    if (time <= 0) {
        return
    }

    suspendCancellableCoroutine<Unit> { continuation ->
        val future = executor.schedule({ continuation.resume(Unit) }, time, unit)
        //注册取消回调
        continuation.invokeOnCancellation { future.cancel(true) }
    }
}