package com.hfy.coroutine.core

import com.hfy.coroutine.CancellationException
import com.hfy.coroutine.Deferred
import com.hfy.coroutine.Job
import com.hfy.coroutine.cancel.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class DeferredCoroutine<T>(context: CoroutineContext) : AbstractCoroutine<T>(context), Deferred<T> {
    override suspend fun await(): T {
        val currentState = state.get()
        return when (currentState) {
            is CoroutineState.Cancelling,
            is CoroutineState.InComplete -> awaitSuspend()
            is CoroutineState.Complete<*> -> {
                coroutineContext[Job]?.isActive?.takeIf { !it }?.let {
                    throw CancellationException("Coroutine is cancelled.")
                }
                //AbstractCoroutine的resumeWith中会设置异常数据，这里取出有异常则抛出
                currentState.exception?.let { throw it } ?: (currentState.value as T)
            }
        }
    }

    private suspend fun awaitSuspend() = suspendCancellableCoroutine<T> { continuation ->
        val disposable = doOnCompleted { result ->
            continuation.resumeWith(result)
        }
        continuation.invokeOnCancellation { disposable.dispose() }
        //取消时，CancellableContinuation内部会调用resumeWithException(CancellationException("string"))
    }
}