package com.hfy.coroutine.cancel

import com.hfy.coroutine.CancellationException
import com.hfy.coroutine.Job
import com.hfy.coroutine.OnCancel
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resumeWithException

/**
 * 可取消的Continuation，包装并代理了原本的continuation
 */
class CancellableContinuation<T>(private val continuation: Continuation<T>) : Continuation<T> by continuation {

    private val state = AtomicReference<CancelState>(CancelState.InComplete)
    private val decision = AtomicReference(CancelDecision.UNDECIDED)

    val isCompleted: Boolean
        get() = when (state.get()) {
            CancelState.InComplete,
            is CancelState.CancelHandler -> false
            is CancelState.Complete<*>,
            CancelState.Cancelled -> true
        }

    override fun resumeWith(result: Result<T>) {
        when {
            decision.compareAndSet(CancelDecision.UNDECIDED, CancelDecision.RESUMED) -> {
                // before getResult called.
                state.set(CancelState.Complete(result.getOrNull(), result.exceptionOrNull()))
            }
            decision.compareAndSet(CancelDecision.SUSPENDED, CancelDecision.RESUMED) -> {
                state.updateAndGet { prev ->
                    when (prev) {
                        is CancelState.Complete<*> -> {
                            throw IllegalStateException("Already completed.")
                        }
                        is CancelState.Cancelled -> {
                            // 已取消，则忽略结果，因为已经在取消的时候返回了CancellationException的结果
                            prev
                        }
                        else -> {
                            CancelState.Complete(result.getOrNull(), result.exceptionOrNull())
                        }
                    }
                }
                if (state.get() is CancelState.Complete<*>) {
                    continuation.resumeWith(result)
                }
            }
        }
    }

    /**
     * suspendCancellableCoroutine的block执行完后，就会立即调用getResult()
     */
    fun getResult(): Any? {
        //注册取消的回调
        installCancelHandler()

        //因为会先执行block，然后执行getResult()，那么如果此时状态是UNDECIDED，说明block内还没有执
        //行resumeWith（因为resumeWith会修改状态），即block切换了函数调用栈，也就是确实被挂起了。那
        //么就把状态设置为SUSPENDED， 并返回COROUTINE_SUSPENDED，COROUTINE_SUSPENDED返回值用于
        //suspendCoroutineUninterceptedOrReturn函数中，表示其block的执行被挂起，且不会立即返回结果
        if (decision.compareAndSet(CancelDecision.UNDECIDED, CancelDecision.SUSPENDED))
            return COROUTINE_SUSPENDED

        //如果没有挂起，就根据当前状态返回结果
        return when (val currentState = state.get()) {
            is CancelState.CancelHandler,
            CancelState.InComplete -> COROUTINE_SUSPENDED
            CancelState.Cancelled -> throw CancellationException("Continuation is cancelled.")
            is CancelState.Complete<*> -> {
                (currentState as CancelState.Complete<T>).let {
                    it.exception?.let { throw it } ?: it.value
                }
            }
        }
    }

    /**
     * 注册取消的回调
     */
    private fun installCancelHandler() {
        if (isCompleted) return
        val parent = continuation.context[Job] ?: return
        //注册取消的回调到launch或者async返回的job中
        parent.invokeOnCancel {
            doCancel()
        }
    }

    /**
     * suspendCancellableCoroutine的block内部调用CancellableContinuation的cancel方法，
     * 未完成的话，实际就会调用launch或者async返回的Job的cancel，作用相同
     */
    fun cancel() {
        if (isCompleted) return
        val parent = continuation.context[Job] ?: return
        parent.cancel()
    }

    /**
     * launch或者async返回的Job或者Deferred就可以调用invokeOnCancel注册取消监听，但在CancellableContinuation内部也
     * 提供注册取消回调的方式，使suspendCancellableCoroutine的参数block lambda内部也能注册取消的回调，目的就是让挂起函数
     * 内部可以响应取消，否则最终返回的Job调用了取消对内部各个挂起函数并不能起作用。
     *
     * 比如挂起函数调用suspendCancellableCoroutine，block内部执行一个Java 8的CompletableFuture，则可以调用
     * invokeOnCancellation注册取消回调，取消时执行CompletableFuture的cancel方法。
     */
    fun invokeOnCancellation(onCancel: OnCancel) {
        val newState = state.updateAndGet { prev ->
            when (prev) {
                //注册回调就是把回调函数放到 CancelHandler 状态中，取消时就会从 CancelHandler 中取出
                CancelState.InComplete -> CancelState.CancelHandler(onCancel)
                is CancelState.CancelHandler -> throw IllegalStateException("It's prohibited to register multiple handlers.")
                is CancelState.Complete<*>,
                CancelState.Cancelled -> prev
            }
        }
        //如果已取消，则直接回调
        if (newState is CancelState.Cancelled) {
            onCancel()
        }
    }

    /**
     * 修改状态为已取消，如果设置了取消的回调就执行 onCancel()
     */
    private fun doCancel() {
        val prevState = state.getAndUpdate { prev ->
            when (prev) {
                is CancelState.CancelHandler,
                CancelState.InComplete -> {
                    CancelState.Cancelled
                }
                CancelState.Cancelled,
                is CancelState.Complete<*> -> {
                    prev
                }
            }
        }
        if (prevState is CancelState.CancelHandler) {
            prevState.onCancel()
        }
        if (state.get() is CancelState.Cancelled) {
            //取消时，恢复并携带CancellationException，因为CancellableContinuation代理了continuation，
            //所以实际就是调用的continuation的resumeWithException函数
            resumeWithException(CancellationException("Cancelled."))
        }
    }
}

/**
 * 仿造官方kotlinx core协程库中的可取消的协程挂起函数 suspendCancellableCoroutine，
 * suspendCoroutineUninterceptedOrReturn的作用就是拿到continuation，所以关键在于
 * 实现一个可取消的Continuation，在取消时可以回调
 *
 *
 * //使用示例
 * suspend fun testSuspendCancellable() {
 *     //正常挂起时，使用suspendCancellableCoroutine
 *     suspendCancellableCoroutine<String> { cancellableContinuation ->
 *         //执行任务...
 *
 *         //注册取消回调
 *         cancellableContinuation.invokeOnCancellation {
 *             //取消任务...
 *         }
 *     }
 *
 *     //未发生挂起时，可以主动判断coroutineContext的Job中的状态
 *     while (coroutineContext[Job]?.isActive == true) {
 *
 *     }
 * }
 *
 */
suspend inline fun <T> suspendCancellableCoroutine(crossinline block: (CancellableContinuation<T>) -> Unit): T =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        val cancellable = CancellableContinuation(continuation.intercepted())
        block(cancellable)
        cancellable.getResult()
    }