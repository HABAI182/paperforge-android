package cn.paperforge.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.LocalTaskRow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replay benchmark for comparing builds on one phone without taking photos: the photos in
 * `<external files>/replay/in` go through the gallery import (detection, flattening, tone, orientation, saving), as
 * the owner adds them, [rounds] times, each into a new document. Wall time per round, every page's own processing
 * record (stage timings), and peak memory over the run (native heap, Java heap, PSS) go to
 * `<external files>/replay/out/<tag>/replay.json`, and the last round's pages as rendered PNGs beside it. The external files folder needs no run-as, so the app under test
 * can be a non-debuggable build. Arguments: `tag`, `rounds` (default 2).
 */
@RunWith(AndroidJUnit4::class)
class ReplayBenchmarkTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    @Test
    fun replayImports() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val tag = args.getString("tag") ?: "replay"
        val rounds = args.getString("rounds")?.toIntOrNull()?.coerceIn(1, 10) ?: 2
        val base = checkNotNull(app.getExternalFilesDir(null))
        val samples =
            File(base, "replay/in").listFiles().orEmpty()
                .filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                .sorted()
        assertTrue("no photos in replay/in", samples.isNotEmpty())
        val out = File(base, "replay/out/$tag").apply { mkdirs() }
        UserSettings.set(SettingKeys.Filter, "WORKBOOK")
        val peakNative = AtomicLong()
        val peakJava = AtomicLong()
        val peakPss = AtomicLong()
        val runtime = Runtime.getRuntime()
        val sampler = Thread {
            var tick = 0
            while (!Thread.currentThread().isInterrupted) {
                peakNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { a, b -> maxOf(a, b) }
                peakJava.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory()) { a, b -> maxOf(a, b) }
                // PSS (all memory the app holds, graphics included) is costly to read: every 0.5 s.
                if (tick++ % 25 == 0) peakPss.accumulateAndGet(Debug.getPss() * 1024) { a, b -> maxOf(a, b) }
                try { Thread.sleep(20) } catch (_: InterruptedException) { return@Thread }
            }
        }
        val report = JSONObject().put("tag", tag).put("samples", samples.size)
            .put("debuggable", (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("cores", Runtime.getRuntime().availableProcessors())
        val roundsJson = JSONArray()
        var lastDocument: String? = null
        sampler.start()
        try {
            val repo = app.repository
            for (round in 1..rounds) {
                val document = repo.newScan()
                lastDocument = document.id
                val started = System.nanoTime()
                repo.importImages(document.id, samples.map(Uri::fromFile))
                // Bounded: two minutes a photo at most.
                val limit = System.nanoTime() + samples.size * 120_000_000_000L
                while (repo.dao.allTasks().any { it.documentId == document.id && it.state in LocalTaskRow.active }) {
                    assertTrue("round $round did not finish", System.nanoTime() < limit)
                    delay(100)
                }
                val seconds = (System.nanoTime() - started) / 1e9
                val pages = repo.dao.pages(document.id)
                assertEquals(samples.size, pages.size)
                val rows = JSONArray()
                for ((index, page) in pages.withIndex())
                    rows.put(
                        JSONObject()
                            .put("file", samples.getOrNull(index)?.name ?: "")
                            .put("method", page.detectionMethod ?: "")
                            .put("metadata", page.processingMetadata ?: "")
                    )
                val failed = repo.dao.allTasks().count { it.documentId == document.id && it.state != cn.paperforge.app.data.LocalTaskState.SUCCEEDED }
                roundsJson.put(JSONObject().put("round", round).put("seconds", seconds).put("failedTasks", failed).put("pages", rows))
            }
        } finally {
            sampler.interrupt()
            sampler.join()
        }
        // After the timed rounds: each page of the last round as the app shows it, so two builds' pages can be compared.
        lastDocument?.let { id ->
            for ((index, page) in app.repository.dao.pages(id).withIndex()) {
                val bitmap = cn.paperforge.app.imaging.ScanProcessor.render(page, 2400)
                File(out, "%02d-final.png".format(index)).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        }
        report.put("rounds", roundsJson)
            .put("peakNativeMb", peakNative.get() / 1048576.0)
            .put("peakJavaMb", peakJava.get() / 1048576.0)
            .put("peakPssMb", peakPss.get() / 1048576.0)
        File(out, "replay.json").writeText(report.toString(1))
        // The app's own folders are closed to adb's shell user (rwxrws---): open this run's results so they can be pulled.
        generateSequence(out) { it.parentFile }.takeWhile { it != base }.forEach { it.setReadable(true, false); it.setExecutable(true, false) }
        out.listFiles().orEmpty().forEach { it.setReadable(true, false) }
    }
}
