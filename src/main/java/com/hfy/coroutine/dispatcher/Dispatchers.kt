package com.hfy.coroutine.dispatcher

import com.hfy.coroutine.dispatcher.ui.AndroidDispatcher

/**
 * 将常用调度器放在Dispatchers类中，方便使用
 */
object Dispatchers {
    val Default by lazy {
        DispatcherContext(DefaultDispatcher)
    }

    val Android by lazy {
        DispatcherContext(AndroidDispatcher)
    }
}