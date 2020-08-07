package com.hfy.coroutine.core

import com.hfy.coroutine.CancellationException
import com.hfy.coroutine.Job
import com.hfy.coroutine.OnCancel
import com.hfy.coroutine.OnComplete
import com.hfy.coroutine.cancel.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*

/**
 * 为了监听协程完成的事件而作为completion参数在启动时传入，
 * 同时作为Job的实现自身也被添加到协程上下文中，方便协程体内部以及其他逻辑获取。
 */
abstract class AbstractCoroutine<T>(context: CoroutineContext) : Job, Continuation<T> {

    protected val state = AtomicReference<CoroutineState>()

    override val context: CoroutineContext


    init {
        state.set(CoroutineState.InComplete())
        this.context = context + this
    }

    val isCompleted
        get() = state.get() is CoroutineState.Complete<*>

    override val isActive: Boolean
        get() = when (val currentState = state.get()) {
            is CoroutineState.Complete<*>,
            is CoroutineState.Cancelling -> false
            is CoroutineState.InComplete -> true
        }

    override fun resumeWith(result: Result<T>) {
        val newState = state.updateAndGet { prevState ->
            when (prevState) {
                //although cancelled, flows of job may work out with the normal result.
                is CoroutineState.Cancelling,
                is CoroutineState.InComplete -> {
                    CoroutineState.Complete(result.getOrNull(), result.exceptionOrNull()).from(prevState)
                }
                is CoroutineState.Complete<*> -> {
                    throw IllegalStateException("Already completed!")
                }
            }
        }

        //协程恢复时，即执行完成，此时回调完成
        newState.notifyCompletion(result)
        newState.clear()
    }

    override suspend fun join() {
        when (state.get()) {
            is CoroutineState.InComplete,
            is CoroutineState.Cancelling -> return joinSuspend()
            is CoroutineState.Complete<*> -> {
                val currentCallingJobState = coroutineContext[Job]?.isActive ?: return
                if (!currentCallingJobState) {
                    throw CancellationException("Coroutine is cancelled.")
                }
                return
            }
        }
    }

    /**
     * 支持取消的joinSuspend
     */
    private suspend fun joinSuspend() = suspendCancellableCoroutine<Unit> { continuation ->
        val disposable = doOnCompleted { result ->
            continuation.resume(Unit)
        }
        //取消时，移除上一步doOnCompleted注册的完成回调，在完成时也就不会在join函数所在协程恢复执行
        continuation.invokeOnCancellation { disposable.dispose() }
    }

    override fun cancel() {
        //这里调用getAndUpdate，返回的是旧状态。
        //因为如果使用updateAndGet获取新状态，判断新状态是否是Cancelling，
        //那么重复调用cancel时新状态也是Cancelling，导致重复回调
        val prevState = state.getAndUpdate { prev ->
            when (prev) {
                //如果未完成，则设置状态为取消中
                is CoroutineState.InComplete -> {
                    CoroutineState.Cancelling()
                }

                //如果取消中或已完成，则无作用
                is CoroutineState.Cancelling,
                is CoroutineState.Complete<*> -> prev
            }
        }

        //如果旧状态是未完成，那么说明改为了取消中，此时回调取消
        if (prevState is CoroutineState.InComplete) {
            prevState.notifyCancellation()
            prevState.clear()
        }
    }

    /**
     * 注册完成时回调
     */
    protected fun doOnCompleted(block: (Result<T>) -> Unit): Disposable {
        val disposable = CompletionHandlerDisposable(this, block)
        val newState = state.updateAndGet { prev ->
            when (prev) {
                //未完成和取消中的状态，都注册回调
                is CoroutineState.InComplete -> {
                    CoroutineState.InComplete().from(prev).with(disposable)
                }
                is CoroutineState.Cancelling -> {
                    CoroutineState.Cancelling().from(prev).with(disposable)
                }
                //已完成则不再修改
                is CoroutineState.Complete<*> -> {
                    prev
                }
            }
        }

        //如果注册时已经完成，则直接回调
        (newState as? CoroutineState.Complete<T>)?.let {
            block(
                when {
                    it.exception != null -> Result.failure(it.exception)
                    it.value != null -> Result.success(it.value)
                    else -> throw IllegalStateException("Won't happen.")
                }
            )
        }
        return disposable
    }

    /**
     * 注册取消时回调
     */
    override fun invokeOnCancel(onCancel: OnCancel): Disposable {
        val disposable = CancellationHandlerDisposable(this, onCancel)
        val newState = state.updateAndGet { prev ->
            when (prev) {
                is CoroutineState.InComplete -> {
                    CoroutineState.InComplete().from(prev).with(disposable)
                }
                is CoroutineState.Cancelling,
                is CoroutineState.Complete<*> -> {
                    prev
                }
            }
        }
        (newState as? CoroutineState.Cancelling)?.let {
            //如果已经处于取消中，则直接触发回调
            onCancel()
        }
        return disposable
    }

    override fun invokeOnCompletion(onComplete: OnComplete): Disposable {
        return doOnCompleted { _ -> onComplete() }
    }

    override fun remove(disposable: Disposable) {
        state.updateAndGet { prev ->
            when (prev) {
                is CoroutineState.InComplete -> {
                    CoroutineState.InComplete().from(prev).without(disposable)
                }
                is CoroutineState.Cancelling -> {
                    CoroutineState.Cancelling().from(prev).without(disposable)
                }
                is CoroutineState.Complete<*> -> {
                    prev
                }
            }
        }
    }
}