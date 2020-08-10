package com.hfy.coroutine

import com.hfy.coroutine.context.CoroutineName
import com.hfy.coroutine.core.DeferredCoroutine
import com.hfy.coroutine.core.StandaloneCoroutine
import com.hfy.coroutine.dispatcher.Dispatchers
import com.hfy.coroutine.scope.CoroutineScope
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

private var coroutineIndex = AtomicInteger(0)

fun CoroutineScope.launch(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit
): Job {
    val completion = StandaloneCoroutine(newCoroutineContext(context))
    block.startCoroutine(completion, completion)
    return completion
}

fun <T> CoroutineScope.async(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T
): Deferred<T> {
    val completion = DeferredCoroutine<T>(newCoroutineContext(context))
    block.startCoroutine(completion, completion)
    return completion
}

/**
 * 包装上下文，加上协程名称，如果创建协程时的上下文没有设置调度器，那么就使用默认调度器
 */
fun CoroutineScope.newCoroutineContext(context: CoroutineContext): CoroutineContext {
    val combined = scopeContext + context + CoroutineName("@coroutine#${coroutineIndex.getAndIncrement()}")
    return if (combined !== Dispatchers.Default && combined[ContinuationInterceptor] == null) {
        combined + Dispatchers.Default
    } else combined
}