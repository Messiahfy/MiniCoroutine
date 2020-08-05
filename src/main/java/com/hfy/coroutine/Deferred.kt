package com.hfy.coroutine

interface Deferred<T> : Job {
    suspend fun await(): T
}