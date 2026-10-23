package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.CoalescingWriter
import com.yashikota.omaigenzo.data.SelectionCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SelectionPersistenceEdgeCaseTest {

    // --- codec -----------------------------------------------------------------------------------

    @Test
    fun anEmptySessionRoundTrips() {
        assertEquals("", SelectionCodec.encode(emptyList()))
        assertArrayEquals(emptyArray<SelectionState>(), SelectionCodec.decode("", 0))
    }

    @Test
    fun everyStateHasItsOwnCharacter() {
        assertEquals("P", SelectionCodec.encode(listOf(SelectionState.PENDING)))
        assertEquals("A", SelectionCodec.encode(listOf(SelectionState.ACCEPT)))
        assertEquals("R", SelectionCodec.encode(listOf(SelectionState.REJECT)))
    }

    @Test
    fun theCodecIsCaseSensitive() {
        assertNull(SelectionCodec.decode("par", 3))
    }

    @Test
    fun aSnapshotWithAnyUnknownCharacterIsRejectedAsAWhole() {
        assertNull(SelectionCodec.decode("PPPPPPPPP?", 10))
        assertNull(SelectionCodec.decode(" AR", 3))
    }

    @Test
    fun aNegativeExpectedSizeIsRejected() {
        assertNull(SelectionCodec.decode("", -1))
    }

    @Test
    fun randomSessionsRoundTripExactly() {
        val random = java.util.Random(1234)
        repeat(50) {
            val states = List(random.nextInt(500)) { SelectionState.entries[random.nextInt(3)] }
            val decoded = SelectionCodec.decode(SelectionCodec.encode(states), states.size)
            assertEquals(states, decoded?.toList())
        }
    }

    // --- writer ----------------------------------------------------------------------------------

    @Test
    fun aFailingWriteDoesNotWedgeTheWriter() {
        val queue = ArrayDeque<Runnable>()
        val written = mutableListOf<String>()
        val writer = CoalescingWriter<String>({ queue.addLast(it) }) { value ->
            if (value == "bad") throw IllegalStateException("disk full")
            written += value
        }

        writer.submit("bad")
        try {
            queue.removeFirst().run()
            fail("the sink's exception should reach the executor")
        } catch (_: IllegalStateException) {
        }
        writer.submit("good")
        assertEquals("a new flush is scheduled after the failure", 1, queue.size)
        queue.removeFirst().run()

        assertEquals(listOf("good"), written)
    }

    @Test
    fun nothingIsScheduledUntilSomethingIsSubmitted() {
        val queue = ArrayDeque<Runnable>()
        CoalescingWriter<String>({ queue.addLast(it) }) { }

        assertTrue(queue.isEmpty())
    }

    @Test
    fun theSameValueSubmittedTwiceAfterAFlushIsWrittenTwice() {
        val queue = ArrayDeque<Runnable>()
        val written = mutableListOf<String>()
        val writer = CoalescingWriter<String>({ queue.addLast(it) }) { written += it }

        writer.submit("x")
        queue.removeFirst().run()
        writer.submit("x")
        queue.removeFirst().run()

        assertEquals(listOf("x", "x"), written)
    }

    @Test
    fun concurrentSubmittersAlwaysEndWithTheLastValueOnDisk() {
        val executor = Executors.newSingleThreadExecutor()
        val written = Collections.synchronizedList(mutableListOf<String>())
        val finalWritten = CountDownLatch(1)
        val writer = CoalescingWriter<String>(executor) { value ->
            written += value
            if (value == "final") finalWritten.countDown()
        }

        val threads = List(8) { worker ->
            Thread { repeat(2_000) { writer.submit("w$worker-$it") } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        writer.submit("final")

        assertTrue(finalWritten.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals("final", written.last())
        assertTrue("a value is never written twice", written.size <= 16_001)
    }
}
