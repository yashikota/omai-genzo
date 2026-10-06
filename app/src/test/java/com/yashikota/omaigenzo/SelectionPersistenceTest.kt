package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.CoalescingWriter
import com.yashikota.omaigenzo.data.SelectionCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SelectionPersistenceTest {

    @Test
    fun codecRoundTripsEveryState() {
        val states = arrayOf(
            SelectionState.PENDING,
            SelectionState.ACCEPT,
            SelectionState.REJECT,
            SelectionState.ACCEPT,
        )
        val encoded = SelectionCodec.encode(states.asList())
        assertEquals("PARA", encoded)
        assertArrayEquals(states, SelectionCodec.decode(encoded, states.size))
    }

    @Test
    fun codecRejectsSnapshotsThatDoNotMatchThePhotoCount() {
        assertNull(SelectionCodec.decode("PAR", 4))
        assertNull(SelectionCodec.decode("PARAA", 4))
        assertNull(SelectionCodec.decode("PAXA", 4))
    }

    @Test
    fun codecIsTinyComparedToPerPhotoJson() {
        val encoded = SelectionCodec.encode(List(10_000) { SelectionState.ACCEPT })
        assertEquals(10_000, encoded.length)
    }

    @Test
    fun codecEncodesLargeSessionsWellInsideAFrameBudget() {
        val states = List(50_000) { SelectionState.entries[it % 3] }
        SelectionCodec.encode(states) // warm up
        val startedAt = System.nanoTime()
        repeat(20) { SelectionCodec.encode(states) }
        val perCallMs = (System.nanoTime() - startedAt) / 20 / 1_000_000.0
        assertTrue("encode of 50k photos took ${perCallMs}ms", perCallMs < 8.0)
    }

    @Test
    fun writerCoalescesBurstsAndKeepsOnlyTheLatestValue() {
        val queue = ArrayDeque<Runnable>()
        val deferred = Executor { queue.addLast(it) }
        val written = mutableListOf<String>()
        val writer = CoalescingWriter<String>(deferred) { written += it }

        writer.submit("a")
        writer.submit("b")
        writer.submit("c")
        assertTrue("nothing may be written on the caller thread", written.isEmpty())
        assertEquals("a burst schedules one flush", 1, queue.size)

        queue.removeFirst().run()
        assertEquals(listOf("c"), written)
    }

    @Test
    fun writerSchedulesAgainAfterAFlush() {
        val queue = ArrayDeque<Runnable>()
        val written = mutableListOf<String>()
        val writer = CoalescingWriter<String>({ queue.addLast(it) }) { written += it }

        writer.submit("1")
        queue.removeFirst().run()
        writer.submit("2")
        queue.removeFirst().run()

        assertEquals(listOf("1", "2"), written)
    }

    @Test
    fun valueSubmittedDuringAWriteIsNotLost() {
        val executor = Executors.newSingleThreadExecutor()
        val inWrite = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = java.util.Collections.synchronizedList(mutableListOf<String>())
        val bothWritten = CountDownLatch(2)
        val writer = CoalescingWriter<String>(executor) { value ->
            if (value == "first") {
                inWrite.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            written += value
            bothWritten.countDown()
        }

        writer.submit("first")
        assertTrue(inWrite.await(5, TimeUnit.SECONDS))
        writer.submit("second")
        release.countDown()

        assertTrue(bothWritten.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(listOf("first", "second"), written)
    }
}
