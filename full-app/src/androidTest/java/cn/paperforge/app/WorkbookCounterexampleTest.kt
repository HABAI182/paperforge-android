package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** The owner's rejected photograph is retained, not replaced by a favourable synthetic fixture. */
@RunWith(AndroidJUnit4::class)
class WorkbookCounterexampleTest {
    private val app
        get() = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private val folder
        get() = File(app.filesDir, "workbook-counterexample").apply { mkdirs() }

    private fun save(bitmap: Bitmap, name: String) {
        File(folder, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun enhance(bitmap: Bitmap): Bitmap {
        val rgba = Mat()
        val rgb = Mat()
        val out = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            EnhancePipeline.apply(rgb, out, "WORKBOOK")
            return Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(out, it)
            }
        } finally {
            listOf(rgba, rgb, out).forEach(Mat::release)
        }
    }

    @Test
    fun defaultWorkbookPdfKeepsRealPixelsAndHonoursSmallFileChoice() = runBlocking {
        val source =
            ScanProcessor.decode(File(app.filesDir, "feedback-scan/output/old-2-crop.png"), 2400)
        val finished =
            try {
                FlatDocument.process(app, source, ai = false)
            } finally {
                source.recycle()
            }
        val image = File(folder, "default-pdf-preview.png")
        try {
            image.outputStream().use {
                check(finished.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            finished.bitmap.recycle()
        }
        val page =
            cn.paperforge.app.data.PageRow(
                "pdf-pixels",
                "benchmark",
                0,
                image.path,
                filter = "ORIGINAL",
                processingMetadata =
                    JSONObject()
                        .put("flatDocument", JSONObject().put("version", FlatDocument.VERSION))
                        .toString(),
            )
        val expected = ScanProcessor.render(page, ScanConfig.edge("STANDARD"))
        val pixels =
            IntArray(expected.width * expected.height).also {
                expected.getPixels(it, 0, expected.width, 0, 0, expected.width, expected.height)
            }
        val rows = JSONArray()
        try {
            for ((name, lossless, quality) in
                listOf(
                    Triple("jpeg-before", 0, 86),
                    Triple("default-after", 1, 86),
                    Triple("small-file", 1, 60),
                )) {
                val snapshot =
                    ScanConfig.replaceForBenchmark(
                        JSONObject().put("workbookLosslessPdf", lossless)
                    )
                val file = File(folder, "workbook-$name.pdf")
                try {
                    cn.paperforge.app.pdf.PdfEngine.scan(
                        listOf(page),
                        file,
                        quality = quality,
                        context = app,
                        searchable = false,
                    )
                } finally {
                    ScanConfig.restoreAfterBenchmark(snapshot)
                }
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { pdf ->
                    val resources = pdf.getPage(0).resources
                    val encoded =
                        resources.xObjectNames
                            .map { resources.getXObject(it) }
                            .filterIsInstance<
                                com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
                            >()
                            .single()
                    val decoded = encoded.image
                    try {
                        assertEquals(expected.width, decoded.width)
                        assertEquals(expected.height, decoded.height)
                        val actual =
                            IntArray(pixels.size).also {
                                decoded.getPixels(
                                    it,
                                    0,
                                    decoded.width,
                                    0,
                                    0,
                                    decoded.width,
                                    decoded.height,
                                )
                            }
                        var total = 0L
                        var changed = 0
                        var header = 0L
                        var countHeader = 0
                        for (i in pixels.indices) {
                            var delta = 0
                            for (shift in listOf(0, 8, 16)) delta +=
                                abs((pixels[i] shr shift and 255) - (actual[i] shr shift and 255))
                            total += delta
                            if (delta != 0) changed++
                            if (i / expected.width < expected.height * .12) {
                                header += delta
                                countHeader++
                            }
                        }
                        if (name == "default-after")
                            assertEquals("default PDF changed processed scan pixels", 0, changed)
                        else
                            assertTrue(
                                "JPEG choice was silently ignored",
                                encoded.cosObject
                                    .getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.FILTER)
                                    .toString()
                                    .contains("DCTDecode"),
                            )
                        rows.put(
                            JSONObject()
                                .put("variant", name)
                                .put("bytes", file.length())
                                .put("changedPixels", changed)
                                .put(
                                    "meanAbsoluteChannelDifference",
                                    total.toDouble() / (pixels.size * 3),
                                )
                                .put(
                                    "top12PercentMeanAbsoluteChannelDifference",
                                    header.toDouble() / (countHeader * 3),
                                )
                        )
                    } finally {
                        decoded.recycle()
                    }
                }
            }
            File(folder, "default-pdf-metrics.json")
                .writeText(
                    JSONObject()
                        .put("realPhoto", true)
                        .put("physicalDevice", false)
                        .put(
                            "scope",
                            "decoded embedded PDF image versus actual processed page; same framing and default quality86; not clean-text truth or viewer rasterization",
                        )
                        .put("rows", rows)
                        .toString(2)
                )
        } finally {
            expected.recycle()
        }
    }

    @Test
    fun compareExistingLearnedGeometryAndSuperResolution() = runBlocking {
        val source =
            ScanProcessor.decode(File(app.filesDir, "feedback-scan/output/old-2-crop.png"), 2400)
        val paper = FlatDocument.sourcePaper(source)
        source.recycle()
        try {
            val start = System.nanoTime()
            val learned = PageDewarp.process(paper, 1f)
            try {
                save(learned.bitmap, "existing-model-geometry")
                val output = enhance(learned.bitmap)
                try {
                    save(output, "existing-model-output")
                } finally {
                    output.recycle()
                }
                File(folder, "existing-model.json")
                    .writeText(
                        JSONObject()
                            .put("algorithm", learned.algorithm)
                            .put("milliseconds", (System.nanoTime() - start) / 1e6)
                            .toString(2)
                    )
            } finally {
                learned.bitmap.recycle()
            }
            val shape = FlatDocument.geometry(paper)
            try {
                val output = enhance(shape.bitmap)
                try {
                    val header =
                        Bitmap.createBitmap(
                            output,
                            0,
                            0,
                            (output.width * .7f).roundToInt(),
                            (output.height * .13f).roundToInt(),
                        )
                    try {
                        save(header, "sr-header-source")
                        val srStart = System.nanoTime()
                        val sr =
                            ScanRetouch.superResolve(
                                app,
                                header,
                                IntArray(header.width * header.height) { -1 },
                                .7f,
                                {},
                                max(header.width, header.height) * 3,
                            )
                        try {
                            save(sr, "sr-header-output")
                        } finally {
                            sr.recycle()
                        }
                        File(folder, "sr-header.json")
                            .writeText(
                                JSONObject()
                                    .put("modelSha256", RetouchModels.SR_SHA)
                                    .put("milliseconds", (System.nanoTime() - srStart) / 1e6)
                                    .toString(2)
                            )
                    } finally {
                        header.recycle()
                    }
                } finally {
                    output.recycle()
                }
            } finally {
                shape.bitmap.recycle()
            }
        } finally {
            paper.recycle()
        }
    }

    @Test
    fun headerMovesRigidlyAndMarginAnnotationsSurvive() {
        val w = 400
        val h = 600
        val curves =
            listOf(90f, 180f, 270f, 360f, 450f).map { target ->
                FloatArray(w) { x -> target + 18f * cos(x * PI.toFloat() / (w - 1)) }
            }
        val result =
            RuledPageRefinement.Result(
                w,
                h,
                floatArrayOf(90f, 180f, 270f, 360f, 450f),
                curves,
                18f,
                true,
            )
        for (x in listOf(0f, .3f, .7f, 1f)) {
            val a = result.inputPoint(PointN(x, .02f))
            val b = result.inputPoint(PointN(x, .04f))
            assertEquals(
                "header Jacobian differs from uniform fit",
                1f / result.fit,
                (b.y - a.y) / .02f,
                .002f,
            )
            assertTrue("source top clipped", result.inputPoint(PointN(x, 0f)).y <= .00001f)
            assertTrue("source bottom clipped", result.inputPoint(PointN(x, 1f)).y >= .99999f)
        }
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.rgb(225, 220, 208))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(214, 141, 111)
            drawRect(375f, 190f, 400f, 290f, paint) // broad edge occluder
            paint.color = Color.BLACK
            drawRect(0f, 540f, 12f, 600f, paint) // exterior corner
            drawRect(0f, 410f, 22f, 455f, paint) // a filled margin figure is real content
            paint.color = Color.RED
            drawRect(0f, 310f, 22f, 390f, paint) // broad saturated annotation at the edge
            paint.strokeWidth = 2f
            drawLine(0f, 50f, 25f, 80f, paint) // fine annotation touching the left margin
            paint.color = Color.rgb(210, 125, 95)
            drawRect(150f, 300f, 230f, 380f, paint) // interior coloured figure
            drawText(
                "-1.5 + x",
                10f,
                145f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.GRAY
                    textSize = 22f
                },
            )
        }
        val rgba = Mat()
        val rgb = Mat()
        try {
            Utils.bitmapToMat(image, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val clean = checkNotNull(WorkbookPaperBoundary.prepare(rgb))
            try {
                val output =
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        Utils.matToBitmap(clean, it)
                    }
                try {
                    assertEquals(Color.WHITE, output.getPixel(390, 240))
                    assertEquals(Color.WHITE, output.getPixel(5, 590))
                    for (y in 40..155) for (x in 0..130) assertEquals(
                        "margin ink modified",
                        image.getPixel(x, y),
                        output.getPixel(x, y),
                    )
                    for (y in 300..379) for (x in 150..229) assertEquals(
                        "interior figure modified",
                        image.getPixel(x, y),
                        output.getPixel(x, y),
                    )
                    for (y in 310..389) for (x in 0..21) assertEquals(
                        "edge colour annotation modified",
                        image.getPixel(x, y),
                        output.getPixel(x, y),
                    )
                    save(image, "boundary-synthetic-source")
                    for (y in 410..454) for (x in 0..21) assertEquals(
                        "dark margin figure modified",
                        image.getPixel(x, y),
                        output.getPixel(x, y),
                    )
                    save(output, "boundary-synthetic-clean")
                } finally {
                    output.recycle()
                }
            } finally {
                clean.release()
            }
        } finally {
            image.recycle()
            listOf(rgba, rgb).forEach(Mat::release)
        }
        val padded = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply {
            drawColor(Color.WHITE)
            drawRect(20f, 20f, w - 20f, h - 20f, Paint().apply { color = Color.rgb(225, 220, 208) })
        }
        val cleanPadding = enhance(padded)
        try {
            for (y in 60 until h - 60 step 7) for (x in 15..35) assertTrue(
                "dewarp padding became a dark border at $x,$y",
                Color.red(cleanPadding.getPixel(x, y)) >= 245,
            )
            save(cleanPadding, "padding-without-halo")
        } finally {
            padded.recycle()
            cleanPadding.recycle()
        }
    }

    @Test
    fun rejectedRealPhotoStagesAndOriginalResolution() = runBlocking {
        val rows = JSONArray()
        val previous = File(app.filesDir, "feedback-scan/output/old-2-crop.png")
        assertTrue("private rejected fixture must be staged", previous.isFile)
        val original = File(folder, "original.jpg")
        assertTrue("highest available owner photo must be staged", original.isFile)
        val framing = JSONObject(File(folder, "framing.json").readText())
        val points = framing.getJSONArray("quad")
        val quad =
            Quad(
                (0..3).map {
                    val p = points.getJSONArray(it)
                    PointN(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
                }
            )
        for (variant in listOf("rejected-crop", "original-framing")) {
            val raw =
                ScanProcessor.decode(if (variant == "rejected-crop") previous else original, 2400)
            val source =
                try {
                    if (variant == "original-framing")
                        ScanProcessor.transform(
                            raw,
                            quad,
                            "ORIGINAL",
                            targetEdge = 2400,
                            interpolation = Imgproc.INTER_CUBIC,
                        )
                    else raw.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    raw.recycle()
                }
            try {
                save(source, "$variant-source")
                for (mode in listOf("before", "after")) {
                    val overrides =
                        if (mode == "before")
                            JSONObject()
                                .put("ruledHeaderExtension", 0)
                                .put("workbookEdgeCleanup", 0)
                                .put("workbookSupportedInkGain", 1.12)
                        else JSONObject().put("workbookDiagnostics", 1)
                    val snapshot = ScanConfig.replaceForBenchmark(overrides)
                    try {
                        val start = System.nanoTime()
                        val paper = FlatDocument.sourcePaper(source)
                        val boundary = WorkbookPaperBoundary.diagnostics()
                        save(paper, "$variant-$mode-paper")
                        val geometry =
                            try {
                                FlatDocument.geometry(paper)
                            } finally {
                                paper.recycle()
                            }
                        try {
                            save(geometry.bitmap, "$variant-$mode-geometry")
                            val output = enhance(geometry.bitmap)
                            try {
                                save(output, "$variant-$mode-output")
                            } finally {
                                output.recycle()
                            }
                            rows.put(
                                JSONObject()
                                    .put("variant", variant)
                                    .put("mode", mode)
                                    .put("width", source.width)
                                    .put("height", source.height)
                                    .put("milliseconds", (System.nanoTime() - start) / 1e6)
                                    .put("geometry", geometry.diagnostics?.let(::JSONObject))
                                    .put("sourceBoundary", boundary)
                                    .put("filterBoundary", WorkbookPaperBoundary.diagnostics())
                            )
                        } finally {
                            geometry.bitmap.recycle()
                        }
                    } finally {
                        ScanConfig.restoreAfterBenchmark(snapshot)
                    }
                }
            } finally {
                source.recycle()
            }
        }
        // Inspect the real automatic framing separately. The matched crop above is NOT detector
        // truth.
        val raw = ScanProcessor.decode(original, 2400)
        try {
            val detection = ScanProcessor.detectResult(raw)
            rows.put(
                JSONObject()
                    .put("automaticFraming", detection.quad?.encode())
                    .put("confidence", detection.confidence)
                    .put("method", detection.method)
            )
            detection.quad?.let { q ->
                val crop =
                    ScanProcessor.transform(
                        raw,
                        q,
                        "ORIGINAL",
                        targetEdge = 2400,
                        interpolation = Imgproc.INTER_CUBIC,
                    )
                try {
                    save(crop, "automatic-crop")
                } finally {
                    crop.recycle()
                }
            }
        } finally {
            raw.recycle()
        }
        File(folder, "metrics.json")
            .writeText(
                JSONObject()
                    .put("realPhoto", true)
                    .put("physicalDevice", false)
                    .put("cleanInkGroundTruth", false)
                    .put("framingEvidence", framing)
                    .put("rows", rows)
                    .toString(2)
            )
    }
}
