package com.yashikota.omaigenzo.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Appends queued log lines to the current log file and rotates it at [maxBytes].
 *
 * Diagnostics must never take the app down: a log that cannot be opened (revoked storage access, a
 * directory owned by another user, a full disk) used to throw out of the writer thread and kill the
 * process. A failed batch is dropped and counted in [failures] instead, and the next batch retries
 * from scratch (including creating the directory), so logging resumes as soon as the file works.
 *
 * The file stays open between batches and its size is tracked in memory: reopening it for every
 * batch cost a mkdirs, a stat, an open and a close per event while the app was otherwise idle.
 * Every batch is still flushed, so `adb pull` always sees complete lines.
 */
class PerfLogWriter(
    private val file: () -> File?,
    private val queue: BlockingQueue<String>,
    private val maxBytes: Long,
    /** Receives every line once it is written, off the caller's thread (Logcat in the app). */
    private val echo: (String) -> Unit = {},
) : AutoCloseable {
    val failures = AtomicLong()

    private var output: OutputStream? = null
    private var openFile: File? = null
    private var sizeBytes = 0L

    /** Blocks for one line, then writes it together with everything already queued. */
    fun writeNextBatch() {
        var line: String? = queue.take()
        val target = file()
        var stream: OutputStream? = null
        if (target != null) {
            stream = try {
                streamFor(target)
            } catch (_: IOException) {
                fail()
            } catch (_: SecurityException) {
                fail()
            }
        }
        while (line != null) {
            echo(line)
            if (stream != null) {
                try {
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    stream.write(bytes)
                    stream.write('\n'.code)
                    sizeBytes += bytes.size + 1
                } catch (_: IOException) {
                    stream = fail()
                }
            }
            line = queue.poll()
        }
        try {
            stream?.flush()
        } catch (_: IOException) {
            fail()
        }
    }

    private fun fail(): OutputStream? {
        failures.incrementAndGet()
        close()
        return null
    }

    private fun streamFor(target: File): OutputStream {
        val current = output
        if (current != null && openFile == target && sizeBytes < maxBytes) return current
        close()
        target.parentFile?.mkdirs()
        if (target.length() >= maxBytes) rotate(target)
        return BufferedOutputStream(FileOutputStream(target, true), 64 * 1024).also {
            output = it
            openFile = target
            sizeBytes = target.length()
        }
    }

    private fun rotate(current: File) {
        val previous = File(current.parentFile, "omai-perf.1.jsonl")
        if (previous.exists()) previous.delete()
        current.renameTo(previous)
    }

    override fun close() {
        try {
            output?.close()
        } catch (_: IOException) {
        }
        output = null
        openFile = null
    }
}
