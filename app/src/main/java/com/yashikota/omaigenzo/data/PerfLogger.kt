package com.yashikota.omaigenzo.data

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object PerfLogger {
    private const val TAG = "OmaiPerf"
    private const val MAX_LOG_BYTES = 64L * 1024L * 1024L
    private val queue = ArrayBlockingQueue<String>(65_536)
    private val dropped = AtomicLong()
    private val writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "omai-perf-writer") }
    private val sampler = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "omai-perf-sampler") }

    @Volatile private var initialized = false

    @Volatile private var outputFile: File? = null

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            outputFile = File(root, "perf/omai-perf.jsonl").apply { parentFile?.mkdirs() }
            initialized = true
            writer.execute { writerLoop() }
            event(
                "session_start",
                "\"sdk\":${Build.VERSION.SDK_INT},\"device\":\"${escape(Build.MANUFACTURER)} ${escape(Build.MODEL)}\"," +
                    "\"hardware\":\"${escape(Build.HARDWARE)}\",\"abis\":\"${escape(Build.SUPPORTED_ABIS.joinToString())}\"," +
                    "\"cores\":${Runtime.getRuntime().availableProcessors()},\"heap_max\":${Runtime.getRuntime().maxMemory()}," +
                    "\"log_path\":\"${escape(outputFile!!.absolutePath)}\"",
            )
            sampler.scheduleAtFixedRate({ sampleSystem(appContext) }, 0L, 1L, TimeUnit.SECONDS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appContext.getSystemService(PowerManager::class.java).addThermalStatusListener { status ->
                    event("thermal", "\"status\":$status")
                }
            }
        }
    }

    fun event(name: String, fields: String = "") {
        if (!initialized) return
        val suffix = if (fields.isEmpty()) "" else ",$fields"
        val line = "{\"wall_ms\":${System.currentTimeMillis()},\"elapsed_ns\":${SystemClock.elapsedRealtimeNanos()}," +
            "\"thread\":\"${escape(Thread.currentThread().name)}\",\"event\":\"${escape(name)}\"$suffix}"
        Log.d(TAG, line)
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    fun path(): String = outputFile?.absolutePath.orEmpty()

    private fun sampleSystem(context: Context) {
        val runtime = Runtime.getRuntime()
        val battery = context.getSystemService(BatteryManager::class.java)
        event(
            "system_sample",
            "\"pss_kb\":${Debug.getPss()},\"native_heap\":${Debug.getNativeHeapAllocatedSize()}," +
                "\"java_used\":${runtime.totalMemory() - runtime.freeMemory()},\"java_total\":${runtime.totalMemory()}," +
                "\"cpu_ms\":${Process.getElapsedCpuTime()},\"battery_pct\":${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}," +
                "\"battery_current_ua\":${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)}," +
                "\"dropped_logs\":${dropped.get()}",
        )
    }

    private fun writerLoop() {
        while (true) {
            val line = queue.take()
            val file = outputFile ?: continue
            if (file.length() >= MAX_LOG_BYTES) rotate(file)
            BufferedWriter(FileWriter(file, true), 64 * 1024).use { output ->
                output.appendLine(line)
                while (true) output.appendLine(queue.poll() ?: break)
            }
        }
    }

    private fun rotate(file: File) {
        val previous = File(file.parentFile, "omai-perf.1.jsonl")
        if (previous.exists()) previous.delete()
        file.renameTo(previous)
    }

    fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }
}
