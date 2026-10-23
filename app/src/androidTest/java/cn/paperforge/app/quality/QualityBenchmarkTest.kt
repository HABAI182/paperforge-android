package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import cn.paperforge.app.ocr.BundledOcrEngine
import cn.paperforge.app.pdf.PdfEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Timing on this AOSP emulator is not physical-phone performance. */
@RunWith(AndroidJUnit4::class)
class QualityBenchmarkTest {
    @Test
    fun recordCorpus() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val baseline =
            InstrumentationRegistry.getArguments().getString("benchMode", "baseline") == "baseline"
        val stage = InstrumentationRegistry.getArguments().getString("benchStage", "all")
        val detectionOnly = stage == "detect"
        val dataset = File(app.filesDir, "bench")
        val manifest = JSONObject(File(dataset, "manifest.json").readText())
        val cases = manifest.getJSONArray("cases")
        val output =
            File(app.filesDir, if (baseline) "quality-baseline" else "quality-current").apply {
                mkdirs()
            }
        val rows = JSONArray()
        var sampledPeakPssKb = 0
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            if (stage == "ocr" && item.isNull("ocr")) continue
            val file = File(dataset, item.getString("file"))
            val decodeStart = SystemClock.elapsedRealtimeNanos()
            val image =
                if (baseline) BaselineScanProcessor.decode(file, 1800)
                else ScanProcessor.decode(file, 1800)
            val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStart) / 1e6
            val start = SystemClock.elapsedRealtimeNanos()
            val result = if (baseline) null else ScanProcessor.detectResult(image)
            val detected = if (baseline) BaselineScanProcessor.detect(image) else result?.quad
            val row =
                JSONObject()
                    .put("file", file.name)
                    .put("scene", item.getString("scene"))
                    .put("synthetic", item.optBoolean("synthetic", false))
                    .put("decode_ms", decodeMs)
                    .put("detect_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                    .put("decoded_width", image.width)
                    .put("decoded_height", image.height)
                    .put("confidence", result?.confidence ?: JSONObject.NULL)
                    .put("method", result?.method ?: "v020")
                    .put(
                        "predicted",
                        detected?.let {
                            JSONArray(it.points.map { p -> JSONArray(listOf(p.x, p.y)) })
                        } ?: JSONObject.NULL,
                    )
            image.recycle()
            val points = item.optJSONArray("quad")
            val quad =
                points?.let { a ->
                    Quad(
                        List(4) { n ->
                            a.getJSONArray(n).let {
                                PointN(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())
                            }
                        }
                    )
                } ?: Quad.Full
            val page =
                PageRow(
                    "bench",
                    "bench",
                    0,
                    file.path,
                    corners = quad.encode(),
                    filter = "ORIGINAL",
                )
            if (!detectionOnly && !item.isNull("ocr")) {
                val ocrStart = SystemClock.elapsedRealtimeNanos()
                val layout =
                    if (baseline) BaselineOcrEngine().layout(page)
                    else BundledOcrEngine().layout(page)
                row.put("ocr", layout.text)
                    .put("ocr_ms", (SystemClock.elapsedRealtimeNanos() - ocrStart) / 1e6)
                row.put("layout", JSONObject(layout.encode()))
            }
            if (stage == "all" && item.getString("scene") == "resolution-12mp") {
                if (baseline) {
                    val legacyDefault = BaselineScanProcessor.render(page, 2800)
                    row.put("pdf_default_render_width", legacyDefault.width)
                        .put("pdf_default_render_height", legacyDefault.height)
                    legacyDefault.recycle()
                }
                val render =
                    if (baseline) BaselineScanProcessor.render(page, 3508)
                    else ScanProcessor.render(page, 3508)
                row.put("high_render_width", render.width).put("high_render_height", render.height)
                File(output, "high-resolution.jpg").outputStream().use {
                    render.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
                render.recycle()
                if (baseline)
                    BaselinePdfEngine.scan(listOf(page), File(output, "resolution-export.pdf"))
                else
                    PdfEngine.scan(
                        listOf(page),
                        File(output, "resolution-export.pdf"),
                        resolution = "HIGH",
                    )
            }
            if (stage == "all" && item.getString("scene") == "ocr-regression") {
                val all = List(10) { page.copy(id = "bw-$it", filter = "BW") }
                // The source baseline engine is the production implementation until Stage 0 is
                // recorded.
                if (baseline) {
                    BaselinePdfEngine.scan(
                        all,
                        File(output, "bw-ten-pages.pdf"),
                        searchable = false,
                    )
                    BaselinePdfEngine.scan(
                        listOf(page),
                        File(output, "searchable.pdf"),
                        context = app,
                        searchable = true,
                    )
                } else {
                    PdfEngine.scan(all, File(output, "bw-ten-pages.pdf"), searchable = false)
                    PdfEngine.scan(
                        listOf(page),
                        File(output, "searchable.pdf"),
                        context = app,
                        searchable = true,
                    )
                }
            }
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            sampledPeakPssKb = maxOf(sampledPeakPssKb, memory.totalPss)
            row.put("sampled_pss_kb", memory.totalPss)
            rows.put(row)
            File(output, "results.json")
                .writeText(
                    JSONObject()
                        .put("mode", if (baseline) "baseline" else "current")
                        .put("dataset", manifest.getString("name"))
                        .put("sampled_peak_pss_kb", sampledPeakPssKb)
                        .put("physical_device", false)
                        .put("rows", rows)
                        .toString(2)
                )
        }
        Unit
    }
}
