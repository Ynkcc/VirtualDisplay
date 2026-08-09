package com.ynk.virtualdisplay.protocol
import java.util.concurrent.atomic.AtomicLong

class SequenceGenerator(initial: Long = 1L) {
    private val gen = AtomicLong(initial)
    fun next(): Long = gen.getAndIncrement()
}
