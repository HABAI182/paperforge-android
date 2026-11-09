package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.AutomaticScanBudget
import cn.paperforge.app.imaging.FlatDocument
import cn.paperforge.app.imaging.LearnedDocumentDetector
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * The stages a real WORKBOOK capture goes through, on the photos in files/phone-samples: still
 * detection, the page at the capture's working size, flattening, and the finished page. An instrument
 * argument `overrides` holds scan parameters as JSON, and `tag` names the output folder.
 */
@RunWith(AndroidJUnit4::class)
class PhoneCaptureTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private fun save(bitmap: Bitmap, file: File) =
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }

    /**
     * The crop decision as the camera makes it: a quad from a small preview frame, then the saved
     * still at full size. The photos are enlarged to a phone's resolution, so fine texture is absent
     * but any size-dependent step still shows up.
     */
    @Test
    fun cropDecisionAtPhoneResolution() {
        val root = File(context.filesDir, "phone-out/crop-flow").apply { mkdirs() }
        val rows = JSONArray()
        for (file in File(context.filesDir, "phone-samples").listFiles().orEmpty().sorted()) {
            val photo = BitmapFactory.decodeFile(file.path)
            val large = Bitmap.createScaledBitmap(photo, photo.width * 3, photo.height * 3, true)
            val scaled = File(root, file.name)
            scaled.outputStream().use { assertTrue(large.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            val frame = Bitmap.createScaledBitmap(photo, photo.width * 720 / photo.height, 720, true)
            val mat = Mat()
            val row = JSONObject().put("file", file.name)
            fun text(q: cn.paperforge.app.model.Quad?) =
                q?.points?.joinToString(";") { "${it.x},${it.y}" } ?: ""
            try {
                Utils.bitmapToMat(frame, mat)
                val preview = LearnedDocumentDetector.detect(mat, true)
                row.put("preview", text(preview.quad)).put("previewConfidence", preview.confidence.toDouble())
                val direct = ScanProcessor.detectFile(scaled, null)
                row.put("still", text(direct.quad))
                    .put("stillMethod", direct.method)
                    .put("stillConfidence", direct.confidence.toDouble())
                val hinted = ScanProcessor.detectFile(scaled, preview.quad, null, null, true)
                row.put("hinted", text(hinted.quad)).put("hintedMethod", hinted.method)
            } finally {
                mat.release()
                frame.recycle()
                large.recycle()
                photo.recycle()
                scaled.delete()
            }
            rows.put(row)
            File(root, "runs.json").writeText(rows.toString(1))
        }
    }

    @Test
    fun captureThePhonePhotos() {
        val arguments = InstrumentationRegistry.getArguments()
        val tag = arguments.getString("tag") ?: "default"
        val overrides = JSONObject(arguments.getString("overrides") ?: "{}")
        val root = File(context.filesDir, "phone-out/$tag").apply { mkdirs() }
        val samples = File(context.filesDir, "phone-samples").listFiles().orEmpty().sorted()
        assertTrue("no photos in files/phone-samples", samples.isNotEmpty())
        val rows = JSONArray()
        val snapshot = ScanConfig.replaceForBenchmark(overrides)
        try {
            for (file in samples) {
                val started = System.nanoTime()
                val detection = ScanProcessor.detectFile(file, null)
                val detectMs = (System.nanoTime() - started) / 1e6
                val quad = checkNotNull(detection.quad) { "no page found in ${file.name}" }
                val page =
                    PageRow(
                        id = "phone-" + file.nameWithoutExtension,
                        documentId = "phone",
                        position = 0,
                        original = file.path,
                        corners = quad.points.joinToString(";") { "${it.x},${it.y}" },
                        filter = "ORIGINAL",
                        detectionMethod = detection.method,
                    )
                val name = file.nameWithoutExtension
                val rendered = ScanProcessor.render(page, AutomaticScanBudget.edge())
                // Enlarged as the import path enlarges it, so this is the page the phone makes.
                val (source, sourceScale) = FlatDocument.workSource(rendered, AutomaticScanBudget.edge())
                if (source !== rendered) rendered.recycle()
                try {
                    save(source, File(root, "$name-1-crop.png"))
                    // The same inputs FlatDocument.process uses, so this is the page the final was toned from.
                    val probe = FlatDocument.sourcePaper(source)
                    val shape =
                        try {
                            FlatDocument.geometry(probe, source)
                        } finally {
                            probe.recycle()
                        }
                    save(shape.bitmap, File(root, "$name-2-flat.png"))
                    shape.bitmap.recycle()
                    val began = System.nanoTime()
                    val result =
                        runBlocking {
                            FlatDocument.process(
                                context,
                                source,
                                1f,
                                ai = true,
                                automatic = true,
                                exactFrame = detection.method.contains("rule-frame"),
                                sourceScale = sourceScale,
                                stackFrame = detection.method.contains("sheet-edge"),
                            )
                        }
                    val finishMs = (System.nanoTime() - began) / 1e6
                    save(result.bitmap, File(root, "$name-3-final.png"))
                    rows.put(
                        JSONObject()
                            .put("file", file.name)
                            .put("method", detection.method)
                            .put("notes", JSONObject(detection.notes.mapValues { it.value.toString() }))
                            .put("quad", quad.points.joinToString(";") { "${it.x},${it.y}" })
                            .put("cropWidth", source.width)
                            .put("cropHeight", source.height)
                            .put("algorithm", result.algorithm)
                            .put("lines", result.lines)
                            .put("detectMs", detectMs)
                            .put("finishMs", finishMs)
                            .put("diagnostics", result.diagnostics ?: "")
                    )
                    result.bitmap.recycle()
                } finally {
                    source.recycle()
                }
                File(root, "runs.json").writeText(rows.toString(1))
            }
        } finally {
            ScanConfig.restoreAfterBenchmark(snapshot)
        }
    }

    /**
     * The camera's live outline against the page the saved photo is cut along, on the photos in
     * files/phone-samples. The frame is the photo at the camera's 1280-pixel analysis size, and the outline
     * goes through the same call the camera screen makes. `gap` is the largest corner distance, as a share
     * of the frame.
     */
    @Test
    fun liveOutlineMatchesStill() {
        val root = File(context.filesDir, "phone-out/live-outline").apply { mkdirs() }
        val rows = JSONArray()
        fun text(q: cn.paperforge.app.model.Quad?) =
            q?.points?.joinToString(";") { "${it.x},${it.y}" } ?: ""
        val samples =
            File(context.filesDir, "phone-samples").listFiles().orEmpty().filter { it.extension == "jpg" }.sorted()
        for (file in samples) {
            val frame = ScanProcessor.decode(file, 1280)
            val mat = Mat()
            try {
                Utils.bitmapToMat(frame, mat)
                val still = ScanProcessor.detectFile(file, null)
                fun gap(q: cn.paperforge.app.model.Quad?) =
                    if (q == null || still.quad == null) 1.0
                    else
                        q.points.zip(still.quad!!.points).maxOf { (a, b) -> kotlin.math.hypot(a.x - b.x, a.y - b.y) }.toDouble()
                val row = JSONObject().put("file", file.name).put("still", text(still.quad)).put("stillMethod", still.method)
                // The detector alone, as the camera had it, and with the camera's edge check (each frame checked,
                // not the steady-outline reuse, so the time is the check's own).
                val variants =
                    listOf<Pair<String, () -> cn.paperforge.app.imaging.DetectionResult>>(
                        "old" to { LearnedDocumentDetector.detect(mat, true) },
                        "live" to { cn.paperforge.app.imaging.EdgeQuad.live(mat) },
                        // The camera's call on a still frame: checked on the second frame, then reused.
                        "steady" to { cn.paperforge.app.imaging.EdgeQuad.liveSteady(mat) },
                    )
                for ((name, run) in variants) {
                    run() // warm
                    val started = System.nanoTime()
                    val result = run()
                    row.put(name, text(result.quad))
                        .put("${name}Method", result.method)
                        .put("${name}Ms", (System.nanoTime() - started) / 1e6)
                        .put("${name}Gap", gap(result.quad))
                }
                rows.put(row)
            } finally {
                mat.release()
                frame.recycle()
            }
            File(root, "live.json").writeText(rows.toString(1))
        }
        assertTrue("no photos in files/phone-samples", samples.isNotEmpty())
    }
}
