package com.yashikota.omaigenzo

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yashikota.omaigenzo.data.PerfLogger
import com.yashikota.omaigenzo.data.PrefetchCoordinator
import com.yashikota.omaigenzo.data.PreviewMetrics
import com.yashikota.omaigenzo.data.ScannedFileEntry
import com.yashikota.omaigenzo.data.ZeroCopyFolderScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end preview latency on a real device, using the production decode pipeline.
 *
 * Put RAW / RAW+JPEG / JPEG files on the device and run (release-grade build, see README):
 *
 *   adb push ./sample-photos/. /sdcard/Android/data/com.yashikota.omaigenzo/files/bench/
 *   adb shell am instrument -w -e class com.yashikota.omaigenzo.PreviewDecodeBenchmark \
 *       [-e benchDir <dir>] [-e thinkMs 300] [-e rounds 3] \
 *       com.yashikota.omaigenzo.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results are printed as INSTRUMENTATION_STATUS lines, logged under the OmaiBench tag, appended to
 * files/perf/bench-results.jsonl and mirrored into the OmaiPerf log as bench_result events.
 */
@RunWith(AndroidJUnit4::class)
class PreviewDecodeBenchmark {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private val benchDir = arguments.getString("benchDir")?.let(::File) ?: File(context.getExternalFilesDir(null), "bench")
    private val thinkMs = arguments.getString("thinkMs")?.toLongOrNull() ?: 300L
    private val rounds = arguments.getString("rounds")?.toIntOrNull() ?: 3

    private lateinit var bridge: LibRawBridge
    private lateinit var photos: List<PhotoItem>

    @Before
    fun setUp() {
        PerfLogger.initialize(context)
        val entries = benchDir.listFiles().orEmpty()
            .filter { it.isFile }
            .map { ScannedFileEntry(fullName = it.name, localPath = it.absolutePath, size = it.length(), modifiedAt = it.lastModified()) }
        photos = ZeroCopyFolderScanner().groupAndCreatePhotoItems(entries)
        assumeTrue("Put at least 4 photos into $benchDir (see the class comment); found ${photos.size}", photos.size >= 4)
        bridge = LibRawBridge(context)
    }

    private var versionSeed = 0L

    /** A fresh cache version makes every call a guaranteed cold decode without clearing caches. */
    private fun freshVersion(photo: PhotoItem): Long = photo.modifiedAt + (++versionSeed) * 1_000_003L

    private fun load(photo: PhotoItem, target: Int, version: Long, priority: DecodePriority = DecodePriority.VISIBLE) = runBlocking {
        bridge.loadPhotoBitmap(
            filePath = photo.fastDisplayPath,
            isRaw = photo.shouldUseRawRenderer(),
            fastMode = true,
            targetMaxDimension = target,
            cacheVersion = version,
            priority = priority,
        )
    }

    @Test
    fun coldFullScreenPreview() {
        val samples = ArrayList<Long>()
        repeat(rounds) {
            photos.forEach { photo ->
                val startedAt = System.nanoTime()
                val bitmap = load(photo, 2048, freshVersion(photo))
                samples += System.nanoTime() - startedAt
                assertNotNull("decode failed for ${photo.fastDisplayPath}", bitmap)
            }
        }
        report("cold_preview_2048", samples)
    }

    @Test
    fun coldGalleryThumbnail() {
        val samples = ArrayList<Long>()
        repeat(rounds) {
            photos.forEach { photo ->
                val startedAt = System.nanoTime()
                val bitmap = load(photo, 512, freshVersion(photo))
                samples += System.nanoTime() - startedAt
                assertNotNull(bitmap)
            }
        }
        report("cold_thumbnail_512", samples)
    }

    @Test
    fun warmCacheHit() {
        val photo = photos.first()
        val version = freshVersion(photo)
        assertNotNull(load(photo, 2048, version))

        val samples = ArrayList<Long>()
        repeat(2_000) {
            val startedAt = System.nanoTime()
            val hit = bridge.peekCached(photo.fastDisplayPath, photo.shouldUseRawRenderer(), true, 2048, version)
            samples += System.nanoTime() - startedAt
            assertNotNull(hit)
        }
        report("cache_hit_peek", samples)
    }

    /** Latency a user would feel: from "swipe" to the next photo being available. */
    @Test
    fun swipeToNextPhotoWithPrefetch() = swipeSequence(prefetchEntries = bridge.maxPrefetchEntries(), label = "swipe_next_with_prefetch")

    @Test
    fun swipeToNextPhotoWithoutPrefetch() = swipeSequence(prefetchEntries = 0, label = "swipe_next_without_prefetch")

    private fun swipeSequence(prefetchEntries: Int, label: String) {
        val coordinator = PrefetchCoordinator(Dispatchers.Default, prefetchEntries)
        val seed = freshVersion(photos.first())
        fun versionOf(photo: PhotoItem) = photo.modifiedAt + seed
        coordinator.onPrefetchRequested = { request ->
            photos.getOrNull(request.index)?.let { photo ->
                bridge.loadPhotoBitmap(
                    filePath = photo.fastDisplayPath,
                    isRaw = photo.shouldUseRawRenderer(),
                    fastMode = true,
                    targetMaxDimension = 2048,
                    cacheVersion = versionOf(photo),
                    priority = DecodePriority.PREFETCH,
                )
            }
        }

        PreviewMetrics.reset()
        val samples = ArrayList<Long>()
        var hits = 0
        for (index in 0 until photos.size - 1) {
            coordinator.updateCurrentIndex(index, photos.size)
            if (thinkMs > 0) Thread.sleep(thinkMs) // the user looking at the current photo

            val next = photos[index + 1]
            val wasCached = bridge.peekCached(next.fastDisplayPath, next.shouldUseRawRenderer(), true, 2048, versionOf(next), record = false) != null
            val startedAt = System.nanoTime()
            assertNotNull(load(next, 2048, versionOf(next)))
            samples += System.nanoTime() - startedAt
            if (wasCached) hits++
        }
        coordinator.cancelAll()
        report(label, samples, extra = "\"prefetch_entries\":$prefetchEntries,\"think_ms\":$thinkMs,\"prefetch_hit_ratio\":${hits.toDouble() / samples.size}")
    }

    private fun report(name: String, nanos: List<Long>, extra: String = "") {
        val sorted = nanos.sorted()
        fun percentile(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / 1e6
        val json = buildString {
            append("{\"bench\":\"$name\",\"samples\":${sorted.size},")
            append("\"p50_ms\":${percentile(0.50)},\"p95_ms\":${percentile(0.95)},\"max_ms\":${sorted.last() / 1e6},")
            append("\"mean_ms\":${sorted.average() / 1e6},")
            append("\"device\":\"${Build.MANUFACTURER} ${Build.MODEL}\",\"sdk\":${Build.VERSION.SDK_INT},\"hardware\":\"${Build.HARDWARE}\",")
            append("\"files\":${photos.size}")
            if (extra.isNotEmpty()) append(",$extra")
            append("}")
        }
        Log.i("OmaiBench", json)
        PerfLogger.event("bench_result", json.removePrefix("{").removeSuffix("}"))
        File(context.getExternalFilesDir(null), "perf").apply { mkdirs() }.let { File(it, "bench-results.jsonl").appendText(json + "\n") }
        instrumentation.sendStatus(0, Bundle().apply { putString("omai.$name", json) })
    }
}
