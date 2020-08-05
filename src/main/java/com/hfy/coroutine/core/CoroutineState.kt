package com.hfy.coroutine.core

sealed class CoroutineState {
    protected var disposableList: RecursiveList<Disposable> = RecursiveList.Nil
        private set

    fun from(state: CoroutineState): CoroutineState {
        this.disposableList = state.disposableList
        return this
    }

    fun with(element: Any): CoroutineState {
        when (element) {
            is Disposable -> this.disposableList = RecursiveList.Cons(element, this.disposableList)
        }
        return this
    }

    fun without(element: Any): CoroutineState {
        when (element) {
            is Disposable -> this.disposableList = this.disposableList.remove(element)
        }
        return this
    }

    fun <T> notifyCompletion(result: Result<T>) {
        this.disposableList.loopOn<CompletionHandlerDisposable<T>> {
            it.onComplete(result)
        }
    }

    fun clear() {
        this.disposableList = RecursiveList.Nil
    }

    class InComplete : CoroutineState()
    class Cancelling : CoroutineState()
    class Complete<T>(val value: T? = null, val exception: Throwable? = null) : CoroutineState()
}