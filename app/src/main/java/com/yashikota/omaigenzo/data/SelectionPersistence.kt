package com.yashikota.omaigenzo.data

import com.yashikota.omaigenzo.SelectionState
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One character per photo, in list order: a few KB even for tens of thousands of photos. */
object SelectionCodec {
    private const val PENDING = 'P'
    private const val ACCEPT = 'A'
    private const val REJECT = 'R'

    fun encode(states: List<SelectionState>): String {
        val chars = CharArray(states.size)
        for (i in chars.indices) {
            chars[i] = when (states[i]) {
                SelectionState.PENDING -> PENDING
                SelectionState.ACCEPT -> ACCEPT
                SelectionState.REJECT -> REJECT
            }
        }
        return String(chars)
    }

    /**
     * The snapshot to restore for the photo list saved with [sessionToken]. The snapshot file wins
     * only when it was written for that same list; sessions saved before it had a file of its own
     * (token 0) keep their [legacySnapshot] next to the photos until the first swipe moves it.
     */
    fun pickSnapshot(sessionToken: Long, snapshotToken: Long, snapshot: String?, legacySnapshot: String?): String? = snapshot?.takeIf { snapshotToken == sessionToken } ?: legacySnapshot

    /** Returns null when the snapshot does not describe exactly [expectedSize] photos. */
    fun decode(encoded: String, expectedSize: Int): Array<SelectionState>? {
        if (encoded.length != expectedSize) return null
        return Array(expectedSize) { i ->
            when (encoded[i]) {
                PENDING -> SelectionState.PENDING
                ACCEPT -> SelectionState.ACCEPT
                REJECT -> SelectionState.REJECT
                else -> return null
            }
        }
    }
}

/**
 * Hands the latest submitted value to [sink] on [executor]. Bursts collapse into one write, and a
 * value submitted while a write is running is written right after it, so nothing is lost and the
 * caller never waits on storage.
 */
class CoalescingWriter<T : Any>(
    private val executor: Executor,
    private val sink: (T) -> Unit,
) {
    private val pending = AtomicReference<T?>(null)
    private val scheduled = AtomicBoolean(false)

    fun submit(value: T) {
        pending.set(value)
        if (scheduled.compareAndSet(false, true)) executor.execute(::drain)
    }

    private fun drain() {
        try {
            pending.getAndSet(null)?.let(sink)
        } finally {
            scheduled.set(false)
            if (pending.get() != null && scheduled.compareAndSet(false, true)) executor.execute(::drain)
        }
    }
}
