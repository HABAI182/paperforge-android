package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.imaging.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Real supplied photos through the production detector, geometry and automatic appearance. */
class WorkbookPaperWhiteTest {
    @Test
    fun contentProtectionWithParameters() {
        val parameters =
            JSONObject(InstrumentationRegistry.getArguments().getString("paperParams") ?: "{}")
        val saved = ScanConfig.replaceForBenchmark(parameters)
        try {
            WorkbookIntelligenceTest().protectsFaintSymbolsColourAndTopology()
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val rows =
                JSONObject(
                        File(app.filesDir, "workbook-intelligence/synthetic-metrics.json")
                            .readText()
                    )
                    .getJSONArray("rows")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.getInt("policy") !in listOf(0, 1, 2, 4)) continue
                for (kind in listOf("print", "pencil", "rules", "colour", "figure")) assertEquals(
                    "calibration damaged $kind",
                    1.0,
                    row.getJSONObject(kind).getDouble("recallAtHalfSourceContrast"),
                    .000001,
                )
            }
        } finally {
            ScanConfig.restoreAfterBenchmark(saved)
        }
    }

    @Test
    fun fixedGeometryBackgroundAblation() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val input = File(app.filesDir, "feedback-20010")
        val output = File(app.filesDir, "feedback-20011").apply { mkdirs() }
        for (name in listOf("book", "math")) {
            val bitmap = ScanProcessor.decode(File(input, "$name-crop.png"), 1400)
            val rgba = Mat()
            val rgb = Mat()
            val enhanced = Mat()
            try {
                Utils.bitmapToMat(bitmap, rgba)
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                for (enabled in 0..1) {
                    val saved =
                        ScanConfig.replaceForBenchmark(
                            JSONObject().put("workbookBackgroundLocalSupport", enabled)
                        )
                    try {
                        EnhancePipeline.apply(rgb, enhanced, "WORKBOOK")
                        val result =
                            Bitmap.createBitmap(
                                enhanced.cols(),
                                enhanced.rows(),
                                Bitmap.Config.ARGB_8888,
                            )
                        try {
                            Utils.matToBitmap(enhanced, result)
                            File(output, "$name-background-$enabled.png").outputStream().use {
                                result.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } finally {
                            result.recycle()
                        }
                    } finally {
                        ScanConfig.restoreAfterBenchmark(saved)
                    }
                }
            } finally {
                bitmap.recycle()
                listOf(rgba, rgb, enhanced).forEach(Mat::release)
            }
        }
    }

    @Test
    fun suppliedPhotosUseActualAutomaticPipeline() = runBlocking {
        val parameters =
            JSONObject(InstrumentationRegistry.getArguments().getString("paperParams") ?: "{}")
        val saved = ScanConfig.replaceForBenchmark(parameters)
        try {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val folder = File(app.filesDir, "feedback-20010").apply { mkdirs() }
            val rows = JSONArray()
            for (name in listOf("book", "math")) {
                val started = System.nanoTime()
                val source = ScanProcessor.decode(File(folder, "$name.jpg"), 2000)
                try {
                    val detection = ScanProcessor.detectResult(source)
                    assertNotNull("$name detection failed", detection.quad)
                    val crop =
                        ScanProcessor.transform(
                            source,
                            detection.quad!!,
                            "ORIGINAL",
                            targetEdge = 1400,
                        )
                    try {
                        val processing = System.nanoTime()
                        val result = FlatDocument.process(app, crop, ai = true, automatic = true)
                        val end = System.nanoTime()
                        try {
                            for ((label, bitmap) in
                                listOf("crop" to crop, "automatic" to result.bitmap)) File(
                                    folder,
                                    "$name-$label.png",
                                )
                                .outputStream()
                                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            rows.put(
                                JSONObject()
                                    .put("photo", name)
                                    .put("quad", detection.quad!!.encode())
                                    .put("method", detection.method)
                                    .put("detectDecodeCropMs", (processing - started) / 1e6)
                                    .put("coreMs", (end - processing) / 1e6)
                                    .put("diagnostics", JSONObject(result.diagnostics ?: "{}"))
                            )
                        } finally {
                            result.bitmap.recycle()
                        }
                    } finally {
                        crop.recycle()
                    }
                } finally {
                    source.recycle()
                }
            }
            File(folder, "metrics.json")
                .writeText(
                    JSONObject()
                        .put("realPhotos", true)
                        .put("physicalPhone", false)
                        .put("rows", rows)
                        .toString(2)
                )
        } finally {
            ScanConfig.restoreAfterBenchmark(saved)
        }
    }

    @Test
    fun backgroundCandidates() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.filesDir, "feedback-20010")
        fun save(mat: Mat, name: String) {
            val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(mat, bitmap)
                File(folder, "$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                bitmap.recycle()
            }
        }
        for (name in listOf("book", "math")) {
            val input = ScanProcessor.decode(File(folder, "$name-crop.png"), 1400)
            val rgba = Mat()
            val rgb = Mat()
            val out = Mat()
            val mask = Mat()
            try {
                Utils.bitmapToMat(input, rgba)
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                WorkbookPaperBoundary.prepare(rgb, true, mask)?.release()
                if (!mask.empty()) save(mask, "$name-exterior")
                File(folder, "$name-boundary.json")
                    .writeText(WorkbookPaperBoundary.diagnostics().toString())
                for ((label, contrast, score) in
                    listOf(
                        Triple("base", 8, 80),
                        Triple("score100", 8, 100),
                        Triple("contrast3", 3, 100),
                        Triple("contrast12", 12, 100),
                    )) {
                    val old =
                        ScanConfig.replaceForBenchmark(
                            JSONObject()
                                .put("workbookComponentContrast", contrast)
                                .put("workbookPrintedComponentContrast", contrast)
                                .put("workbookBackgroundScore", score)
                        )
                    try {
                        EnhancePipeline.apply(rgb, out, "WORKBOOK", 1f)
                        save(out, "$name-tone-$label")
                    } finally {
                        ScanConfig.restoreAfterBenchmark(old)
                    }
                }
            } finally {
                input.recycle()
                listOf(rgba, rgb, out, mask).forEach(Mat::release)
            }
        }
    }

    @Test
    fun weakAnchorCalibration() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val rows = JSONArray()
        for (fraction in listOf(.12f, .16f, .20f, .24f)) {
            val old =
                ScanConfig.replaceForBenchmark(
                    JSONObject().put("workbookWeakAnchorFraction", fraction)
                )
            try {
                val error =
                    runCatching {
                            WorkbookIntelligenceTest().protectsFaintSymbolsColourAndTopology()
                        }
                        .exceptionOrNull()
                rows.put(
                    JSONObject()
                        .put("weakAnchorFraction", fraction.toDouble())
                        .put("passed", error == null)
                        .put("error", error?.message ?: "")
                )
            } finally {
                ScanConfig.restoreAfterBenchmark(old)
            }
        }
        File(app.filesDir, "feedback-20010/weak-calibration.json").writeText(rows.toString(2))
        assertTrue(
            "no source-safe calibration",
            (0 until rows.length()).any { rows.getJSONObject(it).getBoolean("passed") },
        )
    }

    @Test
    fun faintTextRowsAreNotPaperRulings() {
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val pen =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 35, 35)
                textSize = 18f
            }
        for (y in 60..380 step 35) canvas.drawText(
            "const array input value next 0.5 (a + b) / 2; return index;",
            20f,
            y.toFloat(),
            pen,
        )
        val rgba = Mat()
        val ink = Mat()
        val rules = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, ink, Imgproc.COLOR_RGBA2GRAY)
            EnhancePipeline.rulingEvidence(ink, rules)
            val original = ByteArray(900 * 600).also { ink.get(0, 0, it) }
            val evidence = ByteArray(original.size).also { rules.get(0, 0, it) }
            var marked = 0
            var total = 0
            for (i in original.indices) if ((original[i].toInt() and 255) > 10) {
                total++
                if ((evidence[i].toInt() and 255) >= 4) marked++
            }
            assertTrue("text mistaken for rulings: $marked/$total", marked < total * .15)
            pen.strokeWidth = 1.5f
            canvas.drawLine(20f, 480f, 880f, 480f, pen)
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, ink, Imgproc.COLOR_RGBA2GRAY)
            EnhancePipeline.rulingEvidence(ink, rules)
            rules.get(0, 0, evidence)
            assertTrue(
                "faint continuous rule was lost",
                (150..750).count { (evidence[480 * 900 + it].toInt() and 255) >= 4 } > 550,
            )
        } finally {
            bitmap.recycle()
            listOf(rgba, ink, rules).forEach(Mat::release)
        }
    }

    @Test
    fun handShadowIsIlluminationAndKeepsFaintInk() {
        val image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(241, 241, 241))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (x in 140..195) {
            val v = (175 + (x - 140) * 66f / 55).toInt()
            paint.color = Color.rgb(v, v, v)
            canvas.drawRect(x.toFloat(), 305f, (x + 1).toFloat(), 435f, paint)
        }
        paint.color = Color.rgb(211, 150, 112)
        canvas.drawRect(0f, 310f, 105f, 430f, paint)
        canvas.drawOval(65f, 310f, 150f, 430f, paint)
        paint.color = Color.rgb(187, 187, 187)
        paint.strokeWidth = 1.5f
        canvas.drawLine(158f, 345f, 178f, 348f, paint)
        val rgba = Mat()
        val rgb = Mat()
        val mask = Mat()
        try {
            Utils.bitmapToMat(image, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val restored =
                WorkbookPaperBoundary.prepare(rgb, true, mask) ?: error("hand not detected")
            try {
                val values = ByteArray(600 * 800 * 3).also { restored.get(0, 0, it) }
                val hidden = ByteArray(600 * 800).also { mask.get(0, 0, it) }
                assertTrue(
                    "cast shadow stayed dark",
                    (values[(400 * 600 + 170) * 3].toInt() and 255) > 225,
                )
                assertEquals("shadow cleanup erased pencil", 0, hidden[346 * 600 + 165].toInt())
                assertTrue(
                    "pencil beneath shadow disappeared",
                    (values[(346 * 600 + 165) * 3].toInt() and 255) < 235,
                )
            } finally {
                restored.release()
            }
        } finally {
            image.recycle()
            listOf(rgba, rgb, mask).forEach(Mat::release)
        }
    }

    @Test
    fun acceptedWarmPaperSkinSeedCannotVanish() {
        val image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(198, 186, 134))
        val pen = Paint().apply { color = Color.rgb(255, 158, 166) }
        canvas.drawRect(0f, 300f, 140f, 440f, pen)
        val rgba = Mat()
        val rgb = Mat()
        val mask = Mat()
        try {
            Utils.bitmapToMat(image, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val result = WorkbookPaperBoundary.prepare(rgb, true, mask)
            try {
                assertNotNull("accepted warm-paper skin seed disappeared", result)
                val pixels = ByteArray(600 * 800).also { mask.get(0, 0, it) }
                assertTrue(
                    "core removed during fringe growth",
                    pixels[360 * 600 + 70] != 0.toByte(),
                )
            } finally {
                result?.release()
            }
        } finally {
            image.recycle()
            listOf(rgba, rgb, mask).forEach(Mat::release)
        }
    }

    @Test
    fun versionedBakedAppearanceReplaysRetainedInputAndKeepsLegacyLook() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder =
            File(app.cacheDir, "baked-look-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val input = File(folder, "cache-input.png")
        val baked = File(folder, "cache-baked.png")
        val field = File(folder, "cache-grid.gz")
        val image = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.rgb(238, 238, 238))
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.eraseColor(Color.MAGENTA)
            baked.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            image.recycle()
        }
        cn.paperforge.app.model
            .DewarpGrid(320, 480, 0f, 2, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            .write(field)
        val source =
            cn.paperforge.app.data.PageRow(
                "cache-page",
                "fixture",
                0,
                input.path,
                filter = "WORKBOOK",
            )
        for (fingerprint in listOf(ScanConfig.fingerprint(), "stale-appearance", "")) {
            val metadata =
                JSONObject(
                        cn.paperforge.app.data.PageResources.withDewarp(
                            source,
                            input,
                            field,
                            baked,
                            1f,
                            FlatDocument.VERSION,
                            "fixture",
                        )
                    )
                    .put(
                        "flatDocument",
                        JSONObject()
                            .put("appearanceModel", 2)
                            .put("bakedFilter", "WORKBOOK")
                            .put("bakedStrength", 1f)
                            .put("enhancement", fingerprint),
                    )
            val page = source.copy(original = baked.path, processingMetadata = metadata.toString())
            val actual = ScanProcessor.render(page, 480)
            try {
                val green = Color.green(actual.getPixel(100, 100))
                if (fingerprint == "stale-appearance")
                    assertTrue("stale baked pixels reused", green > 220)
                else assertTrue("matching or unversioned look changed", green < 10)
            } finally {
                actual.recycle()
            }
            val raw = ScanProcessor.render(page.copy(filter = "ORIGINAL"), 480)
            try {
                assertTrue(
                    "raw filter no longer uses untinted retained input",
                    Color.green(raw.getPixel(100, 100)) in 236..240,
                )
            } finally {
                raw.recycle()
            }
        }
    }
}
