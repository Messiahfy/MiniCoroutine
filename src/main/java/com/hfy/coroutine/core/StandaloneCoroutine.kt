package com.hfy.coroutine.core

import com.hfy.coroutine.exception.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

class StandaloneCoroutine(context: CoroutineContext) : AbstractCoroutine<Unit>(context) {

    override fun handleJobException(e: Throwable): Boolean {
        super.handleJobException(e)
        //从上下文中取出协程异常处理器，处理异常，如果没有，则使用当前线程的未捕获异常处理器
        context[CoroutineExceptionHandler]?.handleException(context, e)
            ?: Thread.currentThread().let { it.uncaughtExceptionHandler.uncaughtException(it, e) }
        return true
    }
}