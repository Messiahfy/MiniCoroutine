package com.hfy.coroutine

import com.hfy.coroutine.core.DeferredCoroutine
import com.hfy.coroutine.core.StandaloneCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

fun launch(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> Unit): Job {
    val completion = StandaloneCoroutine(context)
    block.startCoroutine(completion)
    return completion
}

fun <T> async(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> T): Deferred<T> {
    val completion = DeferredCoroutine<T>(context)
    block.startCoroutine(completion)
    return completion
}