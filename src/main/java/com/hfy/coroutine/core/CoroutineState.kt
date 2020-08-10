package com.hfy.coroutine.core

import com.hfy.coroutine.Job

sealed class CoroutineState {
    protected var disposableList: RecursiveList<Disposable> = RecursiveList.Nil
        private set
    protected var children: RecursiveList<Job> = RecursiveList.Nil
        private set

    fun from(state: CoroutineState): CoroutineState {
        this.disposableList = state.disposableList
        this.children = state.children
        return this
    }

    fun with(element: Any): CoroutineState {
        when (element) {
            is Disposable -> this.disposableList = RecursiveList.Cons(element, this.disposableList)
            is Job -> this.children = RecursiveList.Cons(element, this.children)
        }
        return this
    }

    fun without(element: Any): CoroutineState {
        when (element) {
            is Disposable -> this.disposableList = this.disposableList.remove(element)
            is Job -> this.children = this.children.remove(element)
        }
        return this
    }

    fun <T> notifyCompletion(result: Result<T>) {
        this.disposableList.loopOn<CompletionHandlerDisposable<T>> {
            it.onComplete(result)
        }
    }

    fun notifyCancellation() {
        this.disposableList.loopOn<CancellationHandlerDisposable> {
            it.onCancel()
        }
    }

    fun clear() {
        this.disposableList = RecursiveList.Nil
        this.children = RecursiveList.Nil
    }

    /**
     * 父协程完成时，现判断子协程是否已经完成
     */
    fun <T> tryComplete(result: Result<T>): CoroutineState {
        //如果children为空，说明没有子协程，直接返回完成状态
        return if (children == RecursiveList.Nil) Complete(result.getOrNull(), result.exceptionOrNull()).from(this)
        //如果不为空，说明有子协程，则需要等待子协程完成
        else CompleteWaitForChildren(result.getOrNull(), result.exceptionOrNull(), this is Cancelling).from(this)
    }

    override fun toString(): String {
        return "CoroutineState.${this.javaClass.simpleName}"
    }

    class InComplete : CoroutineState()
    class Cancelling : CoroutineState()
    class CompleteWaitForChildren<T>(
        val value: T? = null,
        val exception: Throwable? = null,
        val isCancelling: Boolean = false
    ) : CoroutineState() {
        fun copy(
            value: T? = this.value,
            exception: Throwable? = this.exception,
            isCancelling: Boolean = this.isCancelling
        ): CompleteWaitForChildren<T> {
            return CompleteWaitForChildren(value, exception, isCancelling).from(this) as CompleteWaitForChildren<T>
        }

        /**
         * 注册所有子协程的完成回调
         */
        fun tryWaitForChildren(onChildComplete: (Job) -> Unit) {
            children.forEach { child ->
                child.invokeOnCompletion {
                    onChildComplete(child)
                }
            }
        }

        /**
         * 所有子协程完成时才返回Complete，否则依然返回CompleteWaitForChildren
         */
        fun onChildCompleted(job: Job): CoroutineState {
            when (val currentChildren = children) {
                is RecursiveList.Cons -> {
                    if (currentChildren.tail == RecursiveList.Nil && currentChildren.head == job) {
                        //剩下的最后一个子协程完成时，返回完成状态
                        return Complete(value, exception).from(this)
                    }
                }
            }
            //还有子协程未完成，继续返回等待子协程完成的状态
            return CompleteWaitForChildren(value, exception, isCancelling).from(this).without(job)
        }
    }

    class Complete<T>(val value: T? = null, val exception: Throwable? = null) : CoroutineState()
}