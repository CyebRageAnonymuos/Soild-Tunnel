package com.soildtunnel.desktop.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide traffic counters fed by the engine/bridge plumbing.
 * Cumulative per engine run; [UsageStore] samples them into its session
 * history with its own reset-clamping logic.
 */
object TrafficCounters {

    data class Traffic(val downloadBytes: Long, val uploadBytes: Long)

    private val down = AtomicLong(0)
    private val up = AtomicLong(0)

    fun add(d: Long, u: Long) {
        down.addAndGet(d)
        up.addAndGet(u)
    }

    fun traffic(): Traffic = Traffic(down.get(), up.get())

    fun hasData(): Boolean = down.get() > 0L || up.get() > 0L

    fun reset() {
        down.set(0)
        up.set(0)
    }
}
