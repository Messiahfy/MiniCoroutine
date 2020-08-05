package com.hfy.coroutine.dispatcher

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用Java线程池实现的默认调度器
 */
object DefaultDispatcher : Dispatcher {
    private val threadGroup = ThreadGroup("DefaultDispatcher")

    private val threadIndex = AtomicInteger(0)

    private val executor = Executors.newFixedThreadPool(2 * Runtime.getRuntime().availableProcessors()) { runnable ->
        Thread(threadGroup, runnable, "${threadGroup.name}-worker-${threadIndex.getAndIncrement()}").apply {
            //设置Daemon，Java虚拟机中只剩Daemon线程时，虚拟机就会退出
            isDaemon = true
        }
    }

    override fun dispatch(block: () -> Unit) {
        executor.submit(block)
    }
}