package com.hfy.coroutine.scope

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * 正常情况为协调作用域，效果就是让父子协程强关联，父协程取消则子协程也取消，子协程异常也会向上
 * 传播。而还有一种情况为主从作用域，将handleChildException直接返回false，此时父协程就不会对
 * 子协程的异常做出响应，
 */
private class SupervisorCoroutine<T>(
    context: CoroutineContext,
    continuation: Continuation<T>
) : ScopeCoroutine<T>(context, continuation) {

    override fun handleChildException(e: Throwable): Boolean {
        return false
    }

}

/**
 * 创建一个主从作用域。可以发现，协调作用域和主从作用域的区别就是子协程的异常是否继续向上传播。
 * 主从作用域会阻断传播，所以子协程异常就不会影响父协程以及父协程的其他子协程。
 */
suspend fun <R> supervisorScope(block: suspend CoroutineScope.() -> R): R =
    suspendCoroutine { continuation ->
        val coroutine = SupervisorCoroutine(continuation.context, continuation)
        block.startCoroutine(coroutine, coroutine)
    }