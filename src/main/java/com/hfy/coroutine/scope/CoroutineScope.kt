package com.hfy.coroutine.scope

import com.hfy.coroutine.core.AbstractCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * 提供协程作用域的本质作用在于提供协程上下文，通过协程作用域启动的协程都会继承作用域的上下文，
 * 这个继承关系可以在launch和async中调用的newCoroutineContext内部看出来。
 *
 *
 */
interface CoroutineScope {
    val scopeContext: CoroutineContext
}

/**
 * launch等启动协程的函数限定了Receiver为CoroutineScope，所以直接在协程block中可以获取到CoroutineScope，
 * 但是在协程中调用的挂起函数无法访问到CoroutineScope，所以可以通过此函数获取到CoroutineScope。方式实际就
 * 是把suspendCoroutine函数中的continuation包装成ScopeCoroutine，作为block的receiver，也就是自己创建
 * 一个作用域，这个作用域的上下文来源于协程。
 */
suspend fun <R> coroutineScope(block: suspend CoroutineScope.() -> R): R =
    suspendCoroutine { continuation ->
        val coroutine = ScopeCoroutine(continuation.context, continuation)
        //block限定了receiver为CoroutineScope类型，就是这里创建的ScopeCoroutine
        block.startCoroutine(coroutine, coroutine)
    }

/**
 * 官方的ScopeCoroutine会在所有子协程完成后才会调用continuation.resumeWith，我们这里仅仅是简单的只要有一个
 * 子协程完成就直接调用，并未等所有子协程完成，有待完善
 */
internal open class ScopeCoroutine<T>(
    context: CoroutineContext,
    protected val continuation: Continuation<T>
) : AbstractCoroutine<T>(context) {

    override fun resumeWith(result: Result<T>) {
        super.resumeWith(result)
        continuation.resumeWith(result)
    }
}