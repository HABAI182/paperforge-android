package cn.paperforge.app

import android.graphics.*
import android.os.Debug
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

/** Synthetic masks are truth only for generated fixtures. Private photos have no clean truth. */
@RunWith(AndroidJUnit4::class)
class WorkbookIntelligenceTest {
    private val app
        get() = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private val folder
        get() = File(app.filesDir, "workbook-intelligence").apply { mkdirs() }

    private fun save(bitmap: Bitmap, name: String) {
        File(folder, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    /** Independent known-fixture evaluation: invert the generating affine, not LSD detections. */
    private fun knownGridError(grid: DewarpGrid): JSONObject {
        val hy = tan(2.0 * PI / 180)
        val vx = .018
        val determinant = 1 - hy * vx
        fun original(point: PointN): Pair<Double, Double> {
            val input = grid.inputPoint(point)
            val x = input.x * 799 + 10.0
            val y = input.y * 1099 + 14.0
            return (x - vx * y) / determinant to (y - hy * x) / determinant
        }
        fun angle(points: List<Pair<Double, Double>>): Double {
            val mx = points.map { it.first }.average()
            val my = points.map { it.second }.average()
            val numerator = points.sumOf { (it.first - mx) * (it.second - my) }
            val denominator = points.sumOf { (it.first - mx).pow(2) }
            return atan(numerator / denominator) * 180 / PI
        }
        fun solve(fixed: Float, target: Double, vertical: Boolean): Double {
            var low = 0f
            var high = 1f
            repeat(25) {
                val mid = (low + high) / 2
                val point = if (vertical) PointN(mid, fixed) else PointN(fixed, mid)
                val sample = original(point).let { if (vertical) it.first else it.second }
                if (sample < target) low = mid else high = mid
            }
            return (low + high) / 2.0
        }
        val horizontal =
            (80..1040 step 60).map { row ->
                abs(
                    angle(
                        (2..18).map { x ->
                            x / 20.0 * 799 to solve(x / 20f, row.toDouble(), false) * 1099
                        }
                    )
                )
            }
        val vertical =
            listOf(45, 240, 450, 755).map { column ->
                abs(
                    angle(
                        (2..18).map { y ->
                            y / 20.0 * 1099 to solve(y / 20f, column.toDouble(), true) * 799
                        }
                    )
                )
            }
        return JSONObject()
            .put(
                "scope",
                "known synthetic affine and persisted grid, no image detector; not real-photo MAE",
            )
            .put("horizontalMAEDegrees", horizontal.average())
            .put(
                "horizontalP95Degrees",
                horizontal.sorted()[ceil(horizontal.size * .95).toInt() - 1],
            )
            .put("verticalMAEDegrees", vertical.average())
            .put("verticalP95Degrees", vertical.max())
    }

    private fun apply(source: Bitmap, overrides: JSONObject, mode: String = "WORKBOOK"): Bitmap {
        val snapshot = ScanConfig.replaceForBenchmark(overrides)
        val rgba = Mat()
        val rgb = Mat()
        val out = Mat()
        return try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            EnhancePipeline.apply(rgb, out, mode)
            Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(out, it)
            }
        } finally {
            listOf(rgba, rgb, out).forEach(Mat::release)
            ScanConfig.restoreAfterBenchmark(snapshot)
        }
    }

    @Test
    fun learnedGuardOnRealPhoto() = runBlocking {
        assertTrue("full build must retain the existing model", NeuralPaper.available(app))
        val file = File(app.filesDir, "feedback-scan/output/old-2-crop.png")
        assertTrue(file.isFile)
        val source = ScanProcessor.decode(file, 640)
        val rows = JSONArray()
        try {
            save(source, "learned-source")
            for (enabled in 0..1) {
                val snapshot =
                    ScanConfig.replaceForBenchmark(
                        JSONObject().put("workbookIntelligence", enabled)
                    )
                try {
                    val start = System.nanoTime()
                    val result = NeuralPaper.apply(app, source, 1f) {}
                    try {
                        save(result, "learned-$enabled")
                        rows.put(
                            JSONObject()
                                .put("sourceGuard", enabled == 1)
                                .put("milliseconds", (System.nanoTime() - start) / 1e6)
                        )
                        assertEquals(source.width, result.width)
                        assertEquals(source.height, result.height)
                    } finally {
                        result.recycle()
                    }
                } finally {
                    ScanConfig.restoreAfterBenchmark(snapshot)
                }
            }
            File(folder, "learned-metrics.json")
                .writeText(
                    JSONObject()
                        .put("physicalDevice", false)
                        .put("realPhoto", true)
                        .put("edge", 640)
                        .put("modelSha256", NeuralPaper.SHA)
                        .put("rows", rows)
                        .put(
                            "timingScope",
                            "ordered runs: second inference may reuse the restoration cache; not a speed comparison",
                        )
                        .toString(2)
                )
        } finally {
            source.recycle()
        }
    }

    @Test
    fun smoothBackgroundCannotBeRedarkenedAndMaximumDetailRetainsSourceContrast() {
        val w = 120
        val h = 200
        val n = w * h
        val rgb = ByteArray(n * 3) { 230.toByte() }
        val ink = ByteArray(n) { 20 }
        val soft = ink.copyOf()
        val rules = ByteArray(n)
        for (y in listOf(10, 35, 65)) for (x in 0 until w) rules[y * w + x] = 20
        val candidate = ByteArray(n) { 0 } // deliberately wrong, black model candidate
        val support = ByteArray(n)
        fun render(preserve: Boolean) =
            PaperPixelEngine.protectedWorkbook(
                    rgb,
                    ink,
                    soft,
                    rules,
                    candidate,
                    support,
                    ByteArray(n),
                    w,
                    h,
                    floatArrayOf(
                        0f,
                        .95f,
                        1.12f,
                        36f,
                        64f,
                        .22f,
                        .6f,
                        .4f,
                        .25f,
                        .65f,
                        if (preserve) 1f else 0f,
                        2.4f,
                    ),
                )[0]
        val regular = render(false)
        val maximum = render(true)
        assertEquals(
            "old candidate deepened smooth bleed",
            12,
            255 - (regular[25 * w + 60].toInt() and 255),
        )
        assertEquals(
            "maximum detail attenuated source ink",
            20,
            255 - (maximum[25 * w + 60].toInt() and 255),
        )
        assertTrue("rule lost its tone", 255 - (regular[35 * w + 60].toInt() and 255) >= 20)
    }

    @Test
    fun filterEditsReplayRawAppearanceWithoutGeometryOrToneAccumulation() = runBlocking {
        val source = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.rgb(230, 229, 227))
            drawText(
                "-1.5 + x",
                40f,
                180f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 26f
                },
            )
            for (y in 40..560 step 50) drawLine(
                12f,
                y.toFloat(),
                388f,
                y.toFloat(),
                Paint().apply { color = Color.GRAY },
            )
        }
        val input = File(folder, "filter-edit-input.png")
        val baked = File(folder, "filter-edit-baked.png")
        val field = File(folder, "filter-edit-grid-${System.nanoTime()}.gz")
        input.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val oldTone = apply(source, JSONObject())
        baked.outputStream().use { oldTone.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val side = 240
        val identity = FloatArray(side * side * 2)
        // Same half-pixel sample convention and grid density as the saved production field.
        for (y in 0 until side) for (x in 0 until side) {
            identity[2 * (y * side + x)] = (((x + .5f) * 400 / side - .5f) / 399).coerceIn(0f, 1f)
            identity[2 * (y * side + x) + 1] =
                (((y + .5f) * 600 / side - .5f) / 599).coerceIn(0f, 1f)
        }
        DewarpGrid(400, 600, 1f, side, identity).write(field)
        val original =
            cn.paperforge.app.data.PageRow(
                "filter-edit",
                "test",
                0,
                input.path,
                filter = "ORIGINAL",
            )
        val metadata =
            JSONObject(
                    cn.paperforge.app.data.PageResources.withDewarp(
                        original,
                        input,
                        field,
                        baked,
                        1f,
                        FlatDocument.VERSION,
                    )
                )
                .put("flatDocument", JSONObject().put("version", FlatDocument.VERSION))
                .toString()
        val edited =
            cn.paperforge.app.data.PageResources.normalizedAppearance(
                original.copy(
                    original = baked.path,
                    filter = "ORIGINAL",
                    processingMetadata = metadata,
                )
            )
        assertEquals("legacy enhanced page must be labelled workbook", "WORKBOOK", edited.filter)
        val completed = ScanProcessor.render(edited, 600)
        val untouched = ScanProcessor.render(edited.copy(filter = "ORIGINAL"), 600)
        try {
            save(completed, "filter-edit-completed")
            assertEquals("baked width", oldTone.width, completed.width)
            assertEquals("baked height", oldTone.height, completed.height)
            assertArrayEquals(
                "workbook must keep completed pixels",
                IntArray(oldTone.width * oldTone.height).also {
                    oldTone.getPixels(it, 0, oldTone.width, 0, 0, oldTone.width, oldTone.height)
                },
                IntArray(completed.width * completed.height).also {
                    completed.getPixels(
                        it,
                        0,
                        completed.width,
                        0,
                        0,
                        completed.width,
                        completed.height,
                    )
                },
            )
            assertTrue("original must be untoned cropped pixels", source.sameAs(untouched))
        } finally {
            completed.recycle()
            untouched.recycle()
        }
        val changed = edited.copy(enhanceStrength = .7f)
        val rawOcr = ScanProcessor.render(edited.copy(filter = "ORIGINAL"), 600, ocrInput = true)
        try {
            assertTrue("OCR must use the actual original geometry", source.sameAs(rawOcr))
        } finally {
            rawOcr.recycle()
        }
        val trace = DewarpTrace.load(edited)!!
        val rawGeometry = trace.sourceAppearance(600)
        val expected =
            ScanProcessor.transform(
                rawGeometry,
                Quad.Full,
                "WORKBOOK",
                targetEdge = 600,
                strength = .7f,
            )
        val first = ScanProcessor.render(changed, 600)
        val second = ScanProcessor.render(changed, 600)
        try {
            assertTrue("filter ran on baked tone", expected.sameAs(first))
            assertTrue("repeated filters accumulated tone/geometry", first.sameAs(second))
            save(first, "filter-edit-result")
            val pdf = File(folder, "filter-edit-export.pdf")
            cn.paperforge.app.pdf.PdfEngine.scan(
                listOf(changed),
                pdf,
                context = app,
                searchable = false,
                lossless = true,
            )
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf).use { document ->
                val resources = document.getPage(0).resources
                val embedded =
                    resources.xObjectNames
                        .map { resources.getXObject(it) }
                        .filterIsInstance<
                            com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
                        >()
                        .single()
                        .image
                try {
                    assertTrue("export differs from raw-replayed preview", first.sameAs(embedded))
                } finally {
                    embedded.recycle()
                }
            }
        } finally {
            listOf(source, oldTone, rawGeometry, expected, first, second).forEach(Bitmap::recycle)
        }
    }

    @Test
    fun protectsFaintSymbolsColourAndTopology() {
        val w = 900
        val h = 1200
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(238, 238, 240))
        val masks = linkedMapOf<String, Bitmap>()
        fun layer(name: String, color: Int, draw: (Canvas, Paint) -> Unit) {
            val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            draw(
                Canvas(mask),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    textSize = 29f
                    strokeWidth = 1.5f
                },
            )
            masks[name] = mask
            val tint =
                Paint().apply { colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
            canvas.drawBitmap(mask, 0f, 0f, tint)
        }
        layer("print", Color.rgb(45, 45, 45)) { c, p ->
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            for (y in 90..420 step 80) c.drawText(
                "x - 1 = 0.5; (a+b)/2   0123456789",
                70f,
                y.toFloat(),
                p,
            )
        }
        layer("pencil", Color.rgb(213, 213, 213)) { c, p ->
            for (y in 510..690 step 60) c.drawText("- . , :  x² + y = 1; a/b", 90f, y.toFloat(), p)
            p.strokeWidth = 1f
            c.drawLine(170f, 720f, 490f, 725f, p)
        }
        layer("rules", Color.rgb(180, 180, 180)) { c, p ->
            p.strokeWidth = 1f
            for (y in 70..1120 step 60) c.drawLine(35f, y.toFloat(), 865f, y.toFloat(), p)
            for (x in listOf(35, 300, 600, 865)) c.drawLine(
                x.toFloat(),
                800f,
                x.toFloat(),
                1120f,
                p,
            )
        }
        layer("colour", Color.rgb(185, 45, 60)) { c, p -> c.drawText("-1.25 + 3", 120f, 965f, p) }
        layer("figure", Color.rgb(95, 95, 95)) { c, p ->
            p.style = Paint.Style.STROKE
            c.drawCircle(650f, 650f, 57f, p)
            c.drawLine(580f, 730f, 745f, 570f, p)
        }
        save(source, "synthetic-source")
        for ((name, mask) in masks) save(mask, "synthetic-mask-$name")
        val sourcePixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
        val rows = JSONArray()
        val outputs = mutableListOf<Bitmap>()
        try {
            // Variant 3 deliberately disables the source safety downgrade; retain the failed
            // experiment as a negative control, never as a shipping policy or a passing claim.
            for (policy in -1..4) {
                val output =
                    apply(
                        source,
                        JSONObject()
                            .put("workbookIntelligence", if (policy < 0) 0 else 1)
                            .put(
                                "workbookPolicy",
                                if (policy == 3) 1 else if (policy == 4) 0 else max(0, policy),
                            )
                            .put("workbookAllowAmbiguousStrong", if (policy == 3) 1 else 0),
                        mode = if (policy == 4) "CLEAN" else "WORKBOOK",
                    )
                outputs += output
                val pixels = IntArray(w * h).also { output.getPixels(it, 0, w, 0, 0, w, h) }
                val row = JSONObject().put("policy", policy).put("unsafeAblation", policy == 3)
                if (policy >= 0) row.put("decision", WorkbookContent.lastDecision())
                for ((name, mask) in masks) {
                    val truth = IntArray(w * h).also { mask.getPixels(it, 0, w, 0, 0, w, h) }
                    var count = 0
                    var retained = 0
                    var originalContrast = 0.0
                    var contrast = 0.0
                    for (i in truth.indices) if (Color.alpha(truth[i]) >= 100) {
                        val original = 238 - Color.green(sourcePixels[i])
                        if (original < 5) continue
                        count++
                        originalContrast += original
                        contrast += 255 - Color.green(pixels[i])
                        if (255 - Color.green(pixels[i]) >= original * .5) retained++
                    }
                    val recall = retained.toDouble() / max(1, count)
                    assertTrue("fixture $name has no foreground", count > 50)
                    row.put(
                        name,
                        JSONObject()
                            .put("maskPixels", count)
                            .put("recallAtHalfSourceContrast", recall)
                            .put("contrastRatio", contrast / max(1.0, originalContrast)),
                    )
                    if (policy in 0..2 || policy == 4)
                        assertTrue("policy $policy lost $name: $recall", recall >= .98)
                }
                // Use the whole annotated red layer rather than assuming a particular glyph pixel.
                val redCount =
                    pixels.count {
                        Color.red(it) - Color.green(it) > 40 && Color.red(it) - Color.blue(it) > 20
                    }
                row.put("redPixels", redCount)
                if (policy >= 0) assertTrue("red annotations lost", redCount > 100)
                save(output, "synthetic-policy-$policy")
                rows.put(row)
            }
            // Native polish cannot change binary topology at any documented threshold.
            val size = 120 * 100
            val gray = ByteArray(size) { i -> (70 + ((i * 37) % 170)).toByte() }
            val before = gray.copyOf()
            val labels = ByteArray(size) { WorkbookContent.PRINT.toByte() }
            WorkbookStrokePolish.apply(gray, labels, 120, 100)
            for (i in gray.indices) assertEquals(
                (before[i].toInt() and 255) / 32,
                (gray[i].toInt() and 255) / 32,
            )
            File(folder, "synthetic-metrics.json")
                .writeText(
                    JSONObject()
                        .put("synthetic", true)
                        .put("physicalDevice", false)
                        .put("rows", rows)
                        .toString(2)
                )
        } finally {
            outputs.forEach(Bitmap::recycle)
            masks.values.forEach(Bitmap::recycle)
            source.recycle()
        }
    }

    @Test
    fun rotationIsLosslessAndExportKeepsPreviewPixels() = runBlocking {
        val source = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.WHITE)
            drawCircle(150f, 300f, 5f, Paint().apply { color = Color.BLACK })
            drawText(
                "-1.5 + x",
                30f,
                160f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.GRAY
                    textSize = 25f
                },
            )
        }
        val original = File(folder, "rotation-original.png")
        original.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val bytes = original.readBytes()
        try {
            val quarter = ScanProcessor.transform(source, Quad.Full, "ORIGINAL", rotation = 90)
            try {
                val restored =
                    ScanProcessor.transform(quarter, Quad.Full, "ORIGINAL", rotation = 270)
                try {
                    assertEquals(source.width, restored.width)
                    assertEquals(source.height, restored.height)
                    assertTrue("quarter turns resampled pixels", source.sameAs(restored))
                } finally {
                    restored.recycle()
                }
            } finally {
                quarter.recycle()
            }
            val angle = 2.5f
            val geometry = DeskewGeometry(600, 400, angle)
            val once =
                ScanProcessor.transform(
                    source,
                    Quad.Full,
                    "ORIGINAL",
                    rotation = 90,
                    deskewDegrees = angle,
                )
            try {
                assertEquals(geometry.outputWidth, once.width)
                assertEquals(geometry.outputHeight, once.height)
                val point = geometry.forward(PointN((599 - 300) / 600f, 150 / 400f))
                assertTrue(
                    Color.red(
                        once.getPixel(
                            (point.x * once.width).roundToInt(),
                            (point.y * once.height).roundToInt(),
                        )
                    ) < 100
                )
                save(once, "rotation-composed")
            } finally {
                once.recycle()
            }
            val page =
                cn.paperforge.app.data.PageRow(
                    "workbook-test",
                    "workbook-test",
                    0,
                    original.path,
                    filter = "WORKBOOK",
                )
            val preview = ScanProcessor.render(page, ScanConfig.edge("STANDARD"))
            try {
                val pdf = File(folder, "preview-export.pdf")
                cn.paperforge.app.pdf.PdfEngine.scan(
                    listOf(page),
                    pdf,
                    context = app,
                    searchable = false,
                    lossless = true,
                )
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf).use { doc ->
                    val resources = doc.getPage(0).resources
                    val image =
                        resources.xObjectNames
                            .map { resources.getXObject(it) }
                            .filterIsInstance<
                                com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
                            >()
                            .single()
                            .image
                    try {
                        assertEquals(preview.width, image.width)
                        assertEquals(preview.height, image.height)
                        assertTrue("PDF image differs from preview", preview.sameAs(image))
                    } finally {
                        image.recycle()
                    }
                }
            } finally {
                preview.recycle()
            }
            assertArrayEquals("source mutated", bytes, original.readBytes())
        } finally {
            source.recycle()
        }
    }

    @Test
    fun structureRejectsDiagramsAndComposesDistributedAxes() {
        val source = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        val c = Canvas(source)
        c.drawColor(Color.WHITE)
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeWidth = 1.7f
            }
        for (y in 80..1040 step 60) c.drawLine(45f, y.toFloat(), 755f, y.toFloat(), p)
        for (x in listOf(45, 240, 450, 755)) c.drawLine(x.toFloat(), 70f, x.toFloat(), 1050f, p)
        val src = Mat()
        val distorted = Mat()
        val affine = Mat(2, 3, CvType.CV_64FC1)
        val output = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        try {
            Utils.bitmapToMat(source, src)
            affine.put(0, 0, 1.0, .018, -10.0, tan(2.0 * PI / 180), 1.0, -14.0)
            Imgproc.warpAffine(
                src,
                distorted,
                affine,
                src.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar.all(255.0),
            )
            Utils.matToBitmap(distorted, output)
            save(output, "geometry-source")
            val before = WorkbookStructure.analyze(distorted)
            assertTrue("distributed rules not trusted", before.horizontal.confidence >= .7)
            val corrected = FlatDocument.geometry(output)
            try {
                save(corrected.bitmap, "geometry-intelligent")
                Utils.bitmapToMat(corrected.bitmap, distorted)
                val after = WorkbookStructure.analyze(distorted)
                val truth = knownGridError(corrected.grid)
                File(folder, "geometry-metrics.json")
                    .writeText(
                        JSONObject()
                            .put("synthetic", true)
                            .put("sourceHorizontalDegrees", 2.0)
                            .put("before", before.json())
                            .put("after", after.json())
                            .put("knownSyntheticGrid", truth)
                            .put("pipeline", corrected.diagnostics?.let(::JSONObject))
                            .toString(2)
                    )
                assertTrue(
                    "residual horizontal ${after.horizontal.angle}",
                    abs(after.horizontal.angle) < .3,
                )
                assertTrue(
                    "residual vertical ${after.vertical.angle}",
                    abs(after.vertical.angle) < .7,
                )
                assertTrue(truth.toString(), truth.getDouble("horizontalMAEDegrees") < .3)
                assertTrue(truth.toString(), truth.getDouble("horizontalP95Degrees") < .7)
                // The persisted mapping must be finite and locally monotone in both axes.
                for (y in 1..18) for (x in 1..18) {
                    val a = corrected.grid.inputPoint(PointN(x / 20f, y / 20f))
                    val b = corrected.grid.inputPoint(PointN((x + 1) / 20f, y / 20f))
                    val d = corrected.grid.inputPoint(PointN(x / 20f, (y + 1) / 20f))
                    assertTrue(a.x.isFinite() && a.y.isFinite() && b.x > a.x && d.y > a.y)
                }
                val grid = File(folder, "geometry-grid-${System.nanoTime()}.gz")
                corrected.grid.write(grid)
                assertArrayEquals(corrected.grid.values, DewarpGrid.read(grid).values, 0f)
            } finally {
                corrected.bitmap.recycle()
            }
            c.drawColor(Color.WHITE)
            c.drawLine(160f, 350f, 300f, 352f, p)
            c.drawCircle(400f, 500f, 75f, p)
            val rejected = FlatDocument.geometry(source)
            try {
                assertTrue("diagram was warped", source.sameAs(rejected.bitmap))
                assertEquals(0f, rejected.grid.strength, 0f)
            } finally {
                rejected.bitmap.recycle()
            }
        } finally {
            source.recycle()
            output.recycle()
            listOf(src, distorted, affine).forEach(Mat::release)
        }
    }

    @Test
    fun realPhotoSweepAndRepeatedPageStability() = runBlocking {
        val inputs =
            File(app.filesDir, "feedback-scan/output")
                .listFiles()
                ?.filter { it.name.endsWith("-crop.png") }
                ?.sortedBy { it.name }
                ?.toMutableList() ?: mutableListOf()
        val notebook = File(app.filesDir, "flat-feedback-source/latest.jpg")
        if (notebook.isFile) {
            val raw = ScanProcessor.decode(notebook, 2000)
            try {
                val quad =
                    Quad(
                        listOf(
                            PointN(.214f, .212f),
                            PointN(.836f, .230f),
                            PointN(.871f, .882f),
                            PointN(.104f, .878f),
                        )
                    )
                val crop = ScanProcessor.transform(raw, quad, "ORIGINAL", targetEdge = 1600)
                try {
                    val file = File(folder, "notebook-crop.png")
                    file.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    inputs += file
                } finally {
                    crop.recycle()
                }
            } finally {
                raw.recycle()
            }
        }
        assertTrue("private fixture corpus not staged", inputs.size >= 7)
        val rows = JSONArray()
        val times = mutableListOf<Double>()
        var peak = 0L
        for ((index, file) in inputs.withIndex()) {
            val source = ScanProcessor.decode(file, 1600)
            try {
                val name = file.name.removeSuffix("-crop.png")
                save(source, "real-$name-source")
                for (policy in -2..2) {
                    val start = System.nanoTime()
                    val mode = if (policy == -2) "AUTO" else "WORKBOOK"
                    val result =
                        apply(
                            source,
                            JSONObject()
                                .put("workbookIntelligence", if (policy < 0) 0 else 1)
                                .put("workbookPolicy", max(0, policy)),
                            mode,
                        )
                    try {
                        val ms = (System.nanoTime() - start) / 1e6
                        times += ms
                        save(result, "real-$name-policy-$policy")
                        rows.put(
                            JSONObject()
                                .put("input", name)
                                .put("policy", policy)
                                .apply {
                                    if (policy >= 0) put("decision", WorkbookContent.lastDecision())
                                }
                                .put("width", source.width)
                                .put("height", source.height)
                                .put("milliseconds", ms)
                        )
                    } finally {
                        result.recycle()
                    }
                }
                val shapeStart = System.nanoTime()
                val paperSource = FlatDocument.sourcePaper(source)
                val shape =
                    try {
                        FlatDocument.geometry(paperSource)
                    } finally {
                        paperSource.recycle()
                    }
                val shapeMilliseconds = (System.nanoTime() - shapeStart) / 1e6
                try {
                    save(shape.bitmap, "real-$name-geometry")
                    // Reuse the measured grid; these are actual Android appearance stages with
                    // identical new geometry, rather than a second expensive geometry pass.
                    for (enabled in 0..1) {
                        val combined =
                            apply(shape.bitmap, JSONObject().put("workbookIntelligence", enabled))
                        try {
                            save(combined, "real-$name-combined-$enabled")
                        } finally {
                            combined.recycle()
                        }
                    }
                    rows.put(
                        JSONObject()
                            .put("input", name)
                            .put("geometryMilliseconds", shapeMilliseconds)
                            .put("geometry", shape.diagnostics?.let(::JSONObject))
                    )
                } finally {
                    shape.bitmap.recycle()
                }
                if (index == 0) {
                    val rgba = Mat()
                    val rgb = Mat()
                    val dst = Mat()
                    try {
                        Utils.bitmapToMat(source, rgba)
                        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                        val evidence = WorkbookContent.guard(rgb, rgb, dst)
                        File(folder, "real-$name-masks.json")
                            .writeText(evidence.summary().toString(2))
                        val bytes =
                            IntArray(evidence.labels.size) { i ->
                                val label = evidence.labels[i].toInt() and 255
                                Color.rgb(
                                    if (label and WorkbookContent.UNCERTAIN != 0) 200 else 0,
                                    if (
                                        label and
                                            (WorkbookContent.HORIZONTAL or
                                                WorkbookContent.VERTICAL) != 0
                                    )
                                        220
                                    else 0,
                                    if (label and WorkbookContent.SHOW_THROUGH != 0) 220 else 0,
                                )
                            }
                        Bitmap.createBitmap(
                                bytes,
                                source.width,
                                source.height,
                                Bitmap.Config.ARGB_8888,
                            )
                            .also {
                                save(it, "real-$name-masks")
                                it.recycle()
                            }
                    } finally {
                        listOf(rgba, rgb, dst).forEach(Mat::release)
                    }
                }
            } finally {
                source.recycle()
            }
        }
        val before = Debug.getNativeHeapAllocatedSize()
        peak = before
        val pssBefore = Debug.getPss()
        var pssPeak = pssBefore
        // 50 invocations, cycling the same seven photos at 800px. Not 50 unique photos.
        for (i in 0 until 50) {
            val source = ScanProcessor.decode(inputs[i % inputs.size], 800)
            try {
                apply(source, JSONObject()).recycle()
            } finally {
                source.recycle()
            }
            peak = max(peak, Debug.getNativeHeapAllocatedSize())
            pssPeak = max(pssPeak, Debug.getPss())
        }
        System.gc()
        File(folder, "real-metrics.json")
            .writeText(
                JSONObject()
                    .put("synthetic", false)
                    .put("physicalDevice", false)
                    .put("uniquePhotos", inputs.size)
                    .put("cleanGroundTruth", false)
                    .put("rows", rows)
                    .put(
                        "stress",
                        JSONObject()
                            .put("invocations", 50)
                            .put("uniquePhotos", inputs.size)
                            .put("edge", 800)
                            .put("nativeHeapBefore", before)
                            .put("pssBeforeKiB", pssBefore)
                            .put("pssPeakSampledKiB", pssPeak)
                            .put("pssAfterKiB", Debug.getPss())
                            .put("nativeHeapPeakSampled", peak)
                            .put("nativeHeapAfter", Debug.getNativeHeapAllocatedSize()),
                    )
                    .toString(2)
            )
    }
}
