package com.hfy.coroutine.exception

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 可以设置到协程的上下文中，StandaloneCoroutine中会在需要处理异常时取出使用
 *
 * 关于异常处理，要注意的是，在协程被编译成的Suspendlambda的父类BaseContinuationImpl的
 * resumeWith函数中可以看到，它用try-catch语句包住了执行invokeSuspend函数，也就是说协程
 * 中自己未捕获的异常都会在这里被捕获并且放到Result中，所以才可以在创建协程时传入的Continuation
 * 的resumeWith中从result取出异常并自行处理，在我们这里写的协程库中，创建协程时传入的Continuation
 * 就是AbstractCoroutine的子类。所以可以看出，使用标准库启动协程，在协程内部抛出异常也不会造成应用
 * 崩溃，而只是放到result中，而在上层的协程库中，则是根据自行处理的情况而定。
 */
interface CoroutineExceptionHandler : CoroutineContext.Element {

    companion object Key : CoroutineContext.Key<CoroutineExceptionHandler>

    fun handleException(context: CoroutineContext, exception: Throwable)
}

/**
 * 用于方便地生成CoroutineExceptionHandler
 */
inline fun CoroutineExceptionHandler(crossinline handler: (CoroutineContext, Throwable) -> Unit): CoroutineExceptionHandler =
    object : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
        override fun handleException(context: CoroutineContext, exception: Throwable) =
            handler.invoke(context, exception)
    }