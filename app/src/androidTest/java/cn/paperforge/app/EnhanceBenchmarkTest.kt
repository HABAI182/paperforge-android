package cn.paperforge.app

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.imaging.FlatDocument
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Latency of the automatic flatten (the gallery import and the camera's finished page) on the pages in
 * <external files>/bench/in, run the way FlatDocument.prepare runs it for an automatic capture. Each page is warmed
 * up once and then processed `runs` times (instrumentation argument, default 7); the median, P90 and P95 of every
 * stage, the peak native heap (OpenCV's Mats live there) and the first run's page go to <external files>/bench/out.
 * The folder is outside the app sandbox so a non-debuggable build can be measured too
 * (-PpaperforgeDebuggable=false): a debuggable build runs the Kotlin pixel loops many times slower than a release
 * build does.
 */
@RunWith(AndroidJUnit4::class)
class EnhanceBenchmarkTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        val rank = p * (sorted.size - 1)
        val low = sorted[rank.toInt()]
        val high = sorted[minOf(sorted.size - 1, rank.toInt() + 1)]
        return low + (high - low) * (rank - rank.toInt())
    }

    @Test
    fun automaticFlattenLatency() = runBlocking {
        val root = File(app.getExternalFilesDir(null), "bench")
        val pages = File(root, "in").listFiles { f -> f.extension in setOf("jpg", "png") }.orEmpty().sorted()
        assertTrue("no pages in ${root.path}/in", pages.isNotEmpty())
        val out = File(root, "out").apply { deleteRecursively(); mkdirs() }
        val runs = InstrumentationRegistry.getArguments().getString("runs")?.toInt() ?: 7
        val scale = InstrumentationRegistry.getArguments().getString("sourceScale")?.toFloat() ?: 1.6f
        val report = JSONObject().put("runs", runs).put("debuggable", app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        for (page in pages) {
            val source = BitmapFactory.decodeFile(page.path)!!
            val samples = mutableMapOf<String, MutableList<Double>>()
            var peakNative = 0L
            for (run in 0..runs) {
                System.gc()
                val before = Debug.getNativeHeapAllocatedSize()
                val peak = AtomicLong(before)
                val sampler = Thread {
                    while (!Thread.currentThread().isInterrupted) {
                        peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { a, b -> maxOf(a, b) }
                        try { Thread.sleep(10) } catch (_: InterruptedException) { return@Thread }
                    }
                }.apply { start() }
                val result = FlatDocument.process(app, source, 1f, ai = true, automatic = true, sourceScale = scale)
                sampler.interrupt()
                sampler.join()
                if (run == 0) {
                    // Warm-up: kept as the reference output, left out of the statistics.
                    File(out, "${page.nameWithoutExtension}.png").outputStream().use {
                        result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } else {
                    peakNative = maxOf(peakNative, peak.get() - before)
                    val d = JSONObject(result.diagnostics ?: "{}")
                    for (key in listOf("processingMs", "sourcePreparationMs", "geometryMs", "appearanceMs"))
                        samples.getOrPut(key) { mutableListOf() } += d.optDouble(key, 0.0)
                    val steps = d.optJSONObject("steps") ?: JSONObject()
                    for (key in steps.keys()) samples.getOrPut(key) { mutableListOf() } += steps.getDouble(key)
                }
                result.bitmap.recycle()
            }
            val stats = JSONObject()
            for ((key, values) in samples)
                stats.put(
                    key,
                    JSONObject()
                        .put("median", percentile(values, .5))
                        .put("p90", percentile(values, .9))
                        .put("p95", percentile(values, .95))
                        .put("all", JSONArray(values)),
                )
            report.put(
                page.nameWithoutExtension,
                JSONObject()
                    .put("width", source.width)
                    .put("height", source.height)
                    .put("peakNativeMb", peakNative / 1048576.0)
                    .put("stats", stats),
            )
            source.recycle()
        }
        File(out, "bench.json").writeText(report.toString(1))
    }
}
