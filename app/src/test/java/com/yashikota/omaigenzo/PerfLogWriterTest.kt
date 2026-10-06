package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.PerfLogWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ArrayBlockingQueue

class PerfLogWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun queueOf(vararg lines: String) = ArrayBlockingQueue<String>(16).also { it.addAll(lines) }

    @Test
    fun batchesAreAppendedInOrder() {
        val file = File(folder.root, "perf/omai-perf.jsonl")
        val writer = PerfLogWriter({ file }, queueOf("a", "b", "c"), maxBytes = 1_000_000)

        writer.writeNextBatch()

        assertEquals(listOf("a", "b", "c"), file.readLines())
    }

    @Test
    fun anUnwritableLogNeverThrowsIntoTheCaller() {
        // The log's parent is a regular file, so the file can never be opened (EACCES on a device).
        val blocker = folder.newFile("blocker")
        val writer = PerfLogWriter({ File(blocker, "omai-perf.jsonl") }, queueOf("a"), maxBytes = 1_000_000)

        writer.writeNextBatch() // used to propagate and kill the process from the writer thread

        assertEquals(1L, writer.failures.get())
    }

    @Test
    fun loggingRecoversOnceTheFileBecomesWritable() {
        val file = File(folder.root, "perf/omai-perf.jsonl")
        val queue = queueOf("lost")
        val blocker = folder.newFile("not-a-dir")
        var target = File(blocker, "x.jsonl")
        val writer = PerfLogWriter({ target }, queue, maxBytes = 1_000_000)

        writer.writeNextBatch()
        target = file
        queue.add("kept")
        writer.writeNextBatch()

        assertEquals(1L, writer.failures.get())
        assertEquals(listOf("kept"), file.readLines())
    }

    @Test
    fun theParentDirectoryIsCreatedByTheWriter() {
        val file = File(folder.root, "deep/er/log.jsonl")
        PerfLogWriter({ file }, queueOf("x"), maxBytes = 1_000_000).writeNextBatch()
        assertTrue(file.exists())
    }

    @Test
    fun aFullLogIsRotatedKeepingOnePreviousGeneration() {
        val file = File(folder.root, "omai-perf.jsonl").apply { writeText("x".repeat(100)) }
        val writer = PerfLogWriter({ file }, queueOf("fresh"), maxBytes = 50)

        writer.writeNextBatch()

        assertEquals(listOf("fresh"), file.readLines())
        assertEquals("x".repeat(100), File(folder.root, "omai-perf.1.jsonl").readText())
    }
}
