package com.hfy.coroutine.cancel

import com.hfy.coroutine.OnCancel

sealed class CancelState {
    object InComplete : CancelState()
    /**
     * CancellableContinuation只允许注册一个取消回调。CancelHandler就是一个携带回调的Incomplete状态
     */
    class CancelHandler(val onCancel: OnCancel) : CancelState()

    class Complete<T>(val value: T? = null, val exception: Throwable? = null) : CancelState()
    object Cancelled : CancelState()
}

/**
 * 标记对应的挂起函数是否同步返回，即是否实际挂起
 */
enum class CancelDecision {
    UNDECIDED, SUSPENDED, RESUMED
}