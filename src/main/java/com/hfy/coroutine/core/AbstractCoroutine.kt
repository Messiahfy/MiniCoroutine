package com.hfy.coroutine.core

import com.hfy.coroutine.CancellationException
import com.hfy.coroutine.Job
import com.hfy.coroutine.OnCancel
import com.hfy.coroutine.OnComplete
import com.hfy.coroutine.cancel.suspendCancellableCoroutine
import com.hfy.coroutine.scope.CoroutineScope
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*

/**
 * 为了监听协程完成的事件而作为completion参数在启动时传入，
 * 同时作为Job的实现自身也被添加到协程上下文中，方便协程体内部以及其他逻辑获取。
 */
abstract class AbstractCoroutine<T>(context: CoroutineContext) : Job, Continuation<T>, CoroutineScope {

    protected val state = AtomicReference<CoroutineState>()

    override val context: CoroutineContext

    override val scopeContext: CoroutineContext
        get() = context

    /**
     * parentJob为传入的context中的Job
     *
     * 以launch函数为例，内部会创建AbstractCoroutine的子类，构造函数传入的context为作用域的context
     * 加上自行传入的context，所以parentJob就是从作用域的context和自行传入的context中取出，是否
     * 为空取决于我们是否设置。而协程中再使用launch启动子协程，构造函数同样传入context，此时作用域来自
     * 父协程，所以此时context会加上父协程的scope，也就是startCoroutine函数的receiver参数，可以看出，
     * 由于AbstractCoroutine是一个Job子类，所以子协程必然能得到parentJob。协程间可以形成一个树状结构，
     * 孙协程的parentJob为子协程，子协程的parentJob为父协程。
     */
    protected val parentJob = context[Job]

    private var parentCancelDisposable: Disposable? = null

    init {
        state.set(CoroutineState.InComplete())
        this.context = context + this

        //parentJob为父协程，如果父协程不存在，则当前协程处于顶级作用域，
        //如果父协程存在，那么就注册它的取消监听，在父协程取消时，子协程也取消
        parentCancelDisposable = parentJob?.attachChild(this)
    }

    val isCompleted
        get() = state.get() is CoroutineState.Complete<*>

    override val isActive: Boolean
        get() = when (val currentState = state.get()) {
            is CoroutineState.Complete<*>,
            is CoroutineState.Cancelling -> false
            is CoroutineState.CompleteWaitForChildren<*> -> !currentState.isCancelling
            is CoroutineState.InComplete -> true
        }

    override fun resumeWith(result: Result<T>) {
        val newState = state.updateAndGet { prevState ->
            when (prevState) {
                //although cancelled, flows of job may work out with the normal result.
                is CoroutineState.Cancelling,
                is CoroutineState.InComplete -> prevState.tryComplete(result)
                is CoroutineState.CompleteWaitForChildren<*>,
                is CoroutineState.Complete<*> -> {
                    throw IllegalStateException("Already completed!")
                }
            }
        }

        when (newState) {
            //如果需要等待子协程完成，则调用CompleteWaitForChildren的tryWaitForChildren，
            //子协程完成时回调tryCompleteOnChildCompleted
            is CoroutineState.CompleteWaitForChildren<*> -> newState.tryWaitForChildren(::tryCompleteOnChildCompleted)
            //如果没有子协程，直接执行完成
            is CoroutineState.Complete<*> -> makeCompletion(newState as CoroutineState.Complete<T>)
        }
    }

    /**
     * 子协程完成时的回调
     */
    private fun tryCompleteOnChildCompleted(child: Job) {
        val newState = state.updateAndGet { prev ->
            when (prev) {
                is CoroutineState.Cancelling,
                is CoroutineState.InComplete -> {
                    throw IllegalStateException("Should be waiting for children!")
                }
                is CoroutineState.CompleteWaitForChildren<*> -> {
                    prev.onChildCompleted(child)
                }
                is CoroutineState.Complete<*> -> throw IllegalStateException("Already completed!")
            }
        }

        //全部子协程执行完毕
        (newState as? CoroutineState.Complete<T>)?.let {
            makeCompletion(it)
        }
    }

    private fun makeCompletion(newState: CoroutineState.Complete<T>) {
        val result = if (newState.exception == null) {
            Result.success(newState.value)
        } else {
            Result.failure<T>(newState.exception)
        }

        //处理异常
        result.exceptionOrNull()?.let(this::tryHandleException)

        //通知完成回调
        newState.notifyCompletion(result)
        newState.clear()
        parentCancelDisposable?.dispose()
    }


    override suspend fun join() {
        when (state.get()) {
            is CoroutineState.InComplete,
            is CoroutineState.CompleteWaitForChildren<*>,
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
                is CoroutineState.CompleteWaitForChildren<*> -> {
                    prev.copy(isCancelling = true)
                }
            }
        }

        //如果旧状态是未完成，那么说明改为了取消中，此时回调取消
        if (prevState is CoroutineState.InComplete) {
            prevState.notifyCancellation()
        }
        parentCancelDisposable?.dispose()
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
                is CoroutineState.CompleteWaitForChildren<*> -> prev.copy().with(disposable)
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
                is CoroutineState.CompleteWaitForChildren<*> -> prev.copy().with(disposable)
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
                is CoroutineState.CompleteWaitForChildren<*> -> prev.copy().without(disposable)
            }
        }
    }

    /**
     * 区别处理取消异常和其他异常
     */
    private fun tryHandleException(e: Throwable): Boolean {
        return when (e) {
            is CancellationException -> {
                false
            }
            else -> {
                //如果父协程仍然不是根协程，则异常继续向上传播
                (parentJob as? AbstractCoroutine<*>)?.handleChildException(e)?.takeIf { it }
                    ?: handleJobException(e)
            }
        }
    }

    /**
     * 由子协程调用，因为要设计为子协程遇到未捕获的异常要优先向上传播，如果没有父协程才自行处理
     */
    protected open fun handleChildException(e: Throwable): Boolean {
        cancel()
        return tryHandleException(e)
    }

    protected open fun handleJobException(e: Throwable) = false

    /**
     * 父协程关联子协程
     */
    override fun attachChild(child: Job): Disposable {
        state.updateAndGet { prev ->
            when (prev) {
                is CoroutineState.InComplete -> {
                    CoroutineState.InComplete().from(prev).with(child)
                }
                is CoroutineState.Cancelling -> {
                    CoroutineState.Cancelling().from(prev).with(child)
                }
                is CoroutineState.CompleteWaitForChildren<*> -> prev.copy().with(child)
                is CoroutineState.Complete<*> -> throw IllegalStateException("Parent already completed.")
            }
        }

        return invokeOnCancel {
            child.cancel()
        }
    }
}