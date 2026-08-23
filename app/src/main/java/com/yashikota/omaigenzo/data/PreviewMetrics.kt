package com.yashikota.omaigenzo.data

import java.util.concurrent.atomic.AtomicLong

data class PreviewMetricsSnapshot(
    val cacheHits: Long,
    val decodes: Long,
    val totalDecodeNanos: Long,
    val maxDecodeNanos: Long,
) {
    val averageDecodeMillis: Double
        get() = if (decodes == 0L) 0.0 else totalDecodeNanos.toDouble() / decodes / 1_000_000.0
}

object PreviewMetrics {
    private val cacheHits = AtomicLong()
    private val decodes = AtomicLong()
    private val totalDecodeNanos = AtomicLong()
    private val maxDecodeNanos = AtomicLong()

    fun recordCacheHit() {
        cacheHits.incrementAndGet()
    }

    fun recordDecode(elapsedNanos: Long) {
        decodes.incrementAndGet()
        totalDecodeNanos.addAndGet(elapsedNanos)
        maxDecodeNanos.accumulateAndGet(elapsedNanos, ::maxOf)
    }

    fun snapshot() = PreviewMetricsSnapshot(
        cacheHits = cacheHits.get(),
        decodes = decodes.get(),
        totalDecodeNanos = totalDecodeNanos.get(),
        maxDecodeNanos = maxDecodeNanos.get(),
    )

    fun reset() {
        cacheHits.set(0L)
        decodes.set(0L)
        totalDecodeNanos.set(0L)
        maxDecodeNanos.set(0L)
    }
}
