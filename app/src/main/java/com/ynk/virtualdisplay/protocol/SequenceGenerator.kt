package com.ynk.virtualdisplay.protocol
import java.util.concurrent.atomic.AtomicLong

/**
 * 线程安全的递增序号生成器，用于生成控制/请求消息的 sequence 字段。
 *
 * @param initial 初始值，默认 1
 */
class SequenceGenerator(initial: Long = 1L) {
    private val gen = AtomicLong(initial)
    /** 返回下一个递增的序号。 */
    fun next(): Long = gen.getAndIncrement()
}
