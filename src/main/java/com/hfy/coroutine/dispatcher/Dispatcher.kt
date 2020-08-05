package com.hfy.coroutine.dispatcher

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor

/**
 * 协程的调度器，关键在于控制挂起点恢复后的协程执行在哪。
 */
interface Dispatcher {
    fun dispatch(block: () -> Unit)
}

/**
 * 通过拦截器来实现调度，可以作为上下文在启动协程时传入，标准库中创建协程时会调用这里的拦截方法
 */
open class DispatcherContext(private val dispatcher: Dispatcher) :
    AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
        DispatchedContinuation(continuation, dispatcher)
}

private class DispatchedContinuation<T>(val delegate: Continuation<T>, val dispatcher: Dispatcher) : Continuation<T> {
    override val context = delegate.context

    override fun resumeWith(result: Result<T>) {
        //调度的关键就在于恢复的时候，将delegate调度到指定的调度器中执行恢复
        dispatcher.dispatch {
            delegate.resumeWith(result)
        }
    }
}