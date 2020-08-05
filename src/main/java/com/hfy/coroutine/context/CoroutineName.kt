package com.hfy.coroutine.context

import kotlin.coroutines.CoroutineContext

/**
 * 协程名称，方便调试。也是作为一个上下文元素放在协程上下文中
 */
class CoroutineName(val name: String) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CoroutineName>

    override val key = Key

    override fun toString(): String {
        return name
    }
}