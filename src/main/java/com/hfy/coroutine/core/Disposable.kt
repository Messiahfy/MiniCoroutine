package com.hfy.coroutine.core

import com.hfy.coroutine.Job

typealias OnCompleteT<T> = (Result<T>) -> Unit

interface Disposable {
    fun dispose()
}

class CompletionHandlerDisposable<T>(val job: Job, val onComplete: OnCompleteT<T>) : Disposable {
    override fun dispose() {
        job.remove(this)
    }
}