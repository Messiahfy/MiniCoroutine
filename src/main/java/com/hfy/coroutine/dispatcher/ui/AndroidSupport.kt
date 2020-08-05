package com.hfy.coroutine.dispatcher.ui

import android.os.Handler
import android.os.Looper
import com.hfy.coroutine.dispatcher.Dispatcher

object AndroidDispatcher : Dispatcher {
    private val handler = Handler(Looper.getMainLooper())

    override fun dispatch(block: () -> Unit) {
        handler.post(block)
    }
}