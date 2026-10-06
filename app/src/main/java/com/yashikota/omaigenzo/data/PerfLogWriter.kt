package com.yashikota.omaigenzo.data

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Appends queued log lines to the current log file and rotates it at [maxBytes].
 *
 * Diagnostics must never take the app down: a log that cannot be opened (revoked storage access, a
 * directory owned by another user, a full disk) used to throw out of the writer thread and kill the
 * process. A failed batch is dropped and counted in [failures] instead, and the next batch retries
 * from scratch (including creating the directory), so logging resumes as soon as the file works.
 */
class PerfLogWriter(
    private val file: () -> File?,
    private val queue: BlockingQueue<String>,
    private val maxBytes: Long,
) {
    val failures = AtomicLong()

    /** Blocks for one line, then writes it together with everything already queued. */
    fun writeNextBatch() {
        val first = queue.take()
        val target = file() ?: return
        try {
            target.parentFile?.mkdirs()
            if (target.length() >= maxBytes) rotate(target)
            BufferedWriter(FileWriter(target, true), 64 * 1024).use { output ->
                output.appendLine(first)
                while (true) output.appendLine(queue.poll() ?: break)
            }
        } catch (_: IOException) {
            failures.incrementAndGet()
        } catch (_: SecurityException) {
            failures.incrementAndGet()
        }
    }

    private fun rotate(current: File) {
        val previous = File(current.parentFile, "omai-perf.1.jsonl")
        if (previous.exists()) previous.delete()
        current.renameTo(previous)
    }
}
