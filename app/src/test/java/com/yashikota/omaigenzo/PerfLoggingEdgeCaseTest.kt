package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.PerfLogWriter
import com.yashikota.omaigenzo.data.PerfLogger
import com.yashikota.omaigenzo.data.PreviewMetrics
import com.yashikota.omaigenzo.data.PreviewMetricsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PerfLoggingEdgeCaseTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun resetMetrics() = PreviewMetrics.reset()

    private fun queueOf(vararg lines: String) = ArrayBlockingQueue<String>(64).also { it.addAll(lines) }

    // --- escaping --------------------------------------------------------------------------------

    @Test
    fun escapeHandlesEveryJsonControlCharacter() {
        assertEquals("a\\tb\\rc\\nd", PerfLogger.escape("a\tb\rc\nd"))
        assertEquals("\\u0001\\u001f", PerfLogger.escape("\u0001\u001f"))
        assertEquals("\\u0000", PerfLogger.escape("\u0000"))
    }

    @Test
    fun escapeLeavesPrintableAndNonAsciiTextAlone() {
        assertEquals("", PerfLogger.escape(""))
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3ADCIM", PerfLogger.escape("content://com.android.externalstorage.documents/tree/primary%3ADCIM"))
        assertEquals("写真_001.ARW", PerfLogger.escape("写真_001.ARW"))
        assertEquals("\u007f", PerfLogger.escape("\u007f"))
        assertEquals("/", PerfLogger.escape("/"))
    }

    @Test
    fun escapedTextIsAValidJsonStringBody() {
        val nasty = "C:\\a\"b\n\t\u0002end"
        val escaped = PerfLogger.escape(nasty)
        assertFalse(escaped.any { it.code < 0x20 })
        // Every quote in the output is escaped, so the value cannot terminate the JSON string early.
        assertFalse(Regex("(?<!\\\\)\"").containsMatchIn(escaped))
    }

    @Test
    fun eventsBeforeInitializationAreDroppedSilently() {
        PerfLogger.event("unit_test_event", "\"x\":1")
        assertEquals("", PerfLogger.path())
    }

    // --- writer ----------------------------------------------------------------------------------

    @Test
    fun withoutALogFileTheLineIsConsumedAndNotCountedAsAFailure() {
        val queue = queueOf("a")
        val writer = PerfLogWriter({ null }, queue, maxBytes = 1_000)

        writer.writeNextBatch()

        assertTrue(queue.isEmpty())
        assertEquals(0L, writer.failures.get())
    }

    @Test
    fun aLogBelowTheLimitIsAppendedNotRotated() {
        val file = File(folder.root, "omai-perf.jsonl").apply { writeText("old\n") }
        PerfLogWriter({ file }, queueOf("new"), maxBytes = 1_000).writeNextBatch()

        assertEquals(listOf("old", "new"), file.readLines())
        assertFalse(File(folder.root, "omai-perf.1.jsonl").exists())
    }

    @Test
    fun aLogExactlyAtTheLimitIsRotated() {
        val file = File(folder.root, "omai-perf.jsonl").apply { writeText("x".repeat(10)) }
        PerfLogWriter({ file }, queueOf("fresh"), maxBytes = 10).writeNextBatch()

        assertEquals(listOf("fresh"), file.readLines())
        assertEquals("x".repeat(10), File(folder.root, "omai-perf.1.jsonl").readText())
    }

    @Test
    fun rotationReplacesTheOlderGenerationSoDiskUseStaysBounded() {
        val previous = File(folder.root, "omai-perf.1.jsonl").apply { writeText("ancient") }
        val file = File(folder.root, "omai-perf.jsonl").apply { writeText("y".repeat(20)) }

        PerfLogWriter({ file }, queueOf("fresh"), maxBytes = 10).writeNextBatch()

        assertEquals("y".repeat(20), previous.readText())
        assertEquals(setOf("omai-perf.jsonl", "omai-perf.1.jsonl"), folder.root.list()!!.toSet())
    }

    @Test
    fun linesQueuedDuringABatchAreWrittenInTheSameBatch() {
        val file = File(folder.root, "log.jsonl")
        val queue = queueOf(*Array(50) { "line-$it" })

        PerfLogWriter({ file }, queue, maxBytes = 1_000_000).writeNextBatch()

        assertEquals(List(50) { "line-$it" }, file.readLines())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun aSecondBatchAppendsAfterTheFirst() {
        val file = File(folder.root, "log.jsonl")
        val queue = queueOf("1")
        val writer = PerfLogWriter({ file }, queue, maxBytes = 1_000_000)

        writer.writeNextBatch()
        queue.add("2")
        writer.writeNextBatch()

        assertEquals(listOf("1", "2"), file.readLines())
    }

    @Test
    fun theWriterBlocksUntilALineArrives() {
        val file = File(folder.root, "log.jsonl")
        val queue = ArrayBlockingQueue<String>(4)
        val writer = PerfLogWriter({ file }, queue, maxBytes = 1_000_000)
        val finished = CountDownLatch(1)
        val thread = Thread {
            writer.writeNextBatch()
            finished.countDown()
        }.apply { start() }

        assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
        queue.add("late")
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        thread.join()
        assertEquals(listOf("late"), file.readLines())
    }

    // --- metrics ---------------------------------------------------------------------------------

    @Test
    fun anEmptySnapshotReportsZeroLatency() {
        val snapshot = PreviewMetrics.snapshot()
        assertEquals(PreviewMetricsSnapshot(0, 0, 0, 0), snapshot)
        assertEquals(0.0, snapshot.averageDecodeMillis, 0.0)
    }

    @Test
    fun theMaximumIsKeptWhateverTheOrderOfSamples() {
        PreviewMetrics.recordDecode(9_000_000)
        PreviewMetrics.recordDecode(1_000_000)
        PreviewMetrics.recordDecode(5_000_000)

        assertEquals(9_000_000L, PreviewMetrics.snapshot().maxDecodeNanos)
        assertEquals(5.0, PreviewMetrics.snapshot().averageDecodeMillis, 1e-9)
    }

    @Test
    fun resetForgetsEverything() {
        PreviewMetrics.recordCacheHit()
        PreviewMetrics.recordDecode(1_000)

        PreviewMetrics.reset()

        assertEquals(PreviewMetricsSnapshot(0, 0, 0, 0), PreviewMetrics.snapshot())
    }

    @Test
    fun countersAreExactUnderConcurrentDecodes() {
        val threads = List(8) { worker ->
            Thread {
                repeat(10_000) {
                    PreviewMetrics.recordDecode((worker + 1).toLong())
                    PreviewMetrics.recordCacheHit()
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        val snapshot = PreviewMetrics.snapshot()
        assertEquals(80_000L, snapshot.decodes)
        assertEquals(80_000L, snapshot.cacheHits)
        assertEquals((1L..8L).sum() * 10_000L, snapshot.totalDecodeNanos)
        assertEquals(8L, snapshot.maxDecodeNanos)
    }
}
