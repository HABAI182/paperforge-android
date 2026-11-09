package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.imaging.PageDecoder
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.AutoCaptureGate
import cn.paperforge.app.model.CropSafety
import cn.paperforge.app.model.ImageGeometry
import cn.paperforge.app.model.Quad
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * #21 baseline over the public DocUNet camera-photo subset.
 *
 * The corpus has no ground-truth corners, so this only measures what the shipped detection path
 * returns: whether a quad came back, its confidence, method, normalized area and wall-clock time. A
 * returned rectangle is not an accuracy measurement. Each processed photo also gets a 2x2 sheet
 * (source, detected quad, corner detail, metrics) so a human can judge the actual choice.
 */
class DocUnetProbeTest {
    private val decodeEdge = 1800
    private val maxSheets = 20

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(214, 40, 40)
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, 214, 40, 40)
            style = Paint.Style.FILL
        }
    private val cornerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 132, 226)
            style = Paint.Style.FILL
        }
    private val safetyStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 132, 226)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 9f), 0f)
        }

    @Test
    fun detectRealCameraPhotos() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val run = arguments.getString("probeRun", "").orEmpty()
        val override = arguments.getString("probeDetector", "").orEmpty().takeIf { it.isNotBlank() }
        val previewProbe = arguments.getString("probePreview", "false") == "true"
        val detectorSetting = UserSettings.string("detector", "HYBRID")
        val corpus = File(context.filesDir, "docunet")
        val photos =
            corpus
                .listFiles()
                ?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                .orEmpty()
                .sortedBy { naturalKey(it) }
        check(photos.isNotEmpty()) { "Push the DocUNet subset to files/docunet first" }
        val output =
            File(context.filesDir, "docunet-probe" + if (run.isBlank()) "" else "-$run").apply {
                mkdirs()
            }
        output.listFiles()?.filter { it.extension.lowercase() == "jpg" }?.forEach { it.delete() }
        val rows = JSONArray()
        val report =
            JSONObject()
                .put("dataset", "DocUNet public camera-photo subset")
                .put("groundTruthProvided", false)
                .put(
                    "interpretation",
                    "Returned candidates, confidence, area and latency only; no accuracy claim",
                )
                .put("sdk", Build.VERSION.SDK_INT)
                .put("device", Build.MODEL)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("decodeEdge", decodeEdge)
                .put("detectorSetting", detectorSetting)
                .put("detectorOverride", override ?: JSONObject.NULL)
                .put("previewProbe", previewProbe)
                .put("startedAtMillis", System.currentTimeMillis())
                .put("images", photos.size)
                .put("rows", rows)
        var sheets = 0
        photos.forEach { file ->
            val row = JSONObject().put("file", file.name)
            val bitmap =
                try {
                    ScanProcessor.decode(file, decodeEdge)
                } catch (e: Exception) {
                    row.put("error", "decode: " + (e.message ?: e.javaClass.simpleName))
                    rows.put(row)
                    writeReport(output, report)
                    return@forEach
                }
            try {
                row.put("width", bitmap.width).put("height", bitmap.height)
                val started = SystemClock.elapsedRealtimeNanos()
                val outcome = runCatching {
                    ScanProcessor.detectResult(bitmap, detectorOverride = override)
                }
                row.put("detectMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                val result = outcome.getOrNull()
                var safetyQuad: Quad? = null
                if (result == null) {
                    row.put("hasQuad", false)
                    row.put("error", "detect: " + (outcome.exceptionOrNull()?.message ?: "unknown"))
                } else {
                    val quad = result.quad
                    row.put("hasQuad", quad != null)
                    row.put("confidence", result.confidence.toDouble())
                    row.put("method", result.method)
                    row.put("detectorElapsedMs", result.elapsedMs.toDouble())
                    row.put("area", quad?.area?.toDouble() ?: 0.0)
                    row.put("quad", quadJson(quad))
                    row.put(
                        "diagnostics",
                        JSONObject(result.diagnostics.mapValues { it.value.toDouble() }.toMap()),
                    )
                    if (quad != null) {
                        // #21 A/B evidence: the safety ring actually used for the crop, the
                        // side ratio, and whether the auto-capture gate arms on this photo.
                        val safety =
                            CropSafety.expand(
                                quad,
                                bitmap.width,
                                bitmap.height,
                                ScanConfig.float("cropSafetyMargin", CropSafety.Margin),
                            )
                        val sides = ImageGeometry.pixelSides(quad, bitmap.width, bitmap.height)
                        val aspect = max(sides.first, sides.second) / min(sides.first, sides.second)
                        val gate =
                            AutoCaptureGate(
                                UserSettings[SettingKeys.StableMs].toLong(),
                                UserSettings[SettingKeys.CooldownMs].toLong(),
                                UserSettings[SettingKeys.CaptureArea],
                            )
                        var armed = false
                        for (step in 0..12) {
                            if (
                                gate.update(
                                    quad,
                                    step * 200L,
                                    true,
                                    frameWidth = bitmap.width,
                                    frameHeight = bitmap.height,
                                )
                            )
                                armed = true
                        }
                        safetyQuad = safety
                        row.put("aspect", aspect.toDouble())
                        row.put("cropQuad", quadJson(safety))
                        row.put("cropArea", safety.area.toDouble())
                        row.put(
                            "cropMargin",
                            ScanConfig.float("cropSafetyMargin", CropSafety.Margin).toDouble(),
                        )
                        row.put(
                            "areaFloor",
                            gate.areaFloor(quad, bitmap.width, bitmap.height).toDouble(),
                        )
                        row.put("autoArm", armed)
                        val decoded = runCatching {
                            PageDecoder.crop(file, quad, 2400).let { decodedImage ->
                                try {
                                    val cropped =
                                        ScanProcessor.transform(
                                            decodedImage.bitmap,
                                            decodedImage.quad,
                                            "ORIGINAL",
                                        )
                                    try {
                                        File(output, file.nameWithoutExtension + "-crop.jpg")
                                            .outputStream()
                                            .use {
                                                cropped.compress(Bitmap.CompressFormat.JPEG, 90, it)
                                            }
                                        row.put("cropWidth", cropped.width)
                                            .put("cropHeight", cropped.height)
                                    } finally {
                                        cropped.recycle()
                                    }
                                } finally {
                                    decodedImage.bitmap.recycle()
                                }
                            }
                        }
                        decoded.exceptionOrNull()?.let {
                            row.put("cropError", it.message ?: it.javaClass.simpleName)
                        }
                    }
                }
                if (previewProbe) {
                    val previewStarted = SystemClock.elapsedRealtimeNanos()
                    val preview = runCatching {
                        ScanProcessor.detectResult(
                            bitmap,
                            preview = true,
                            detectorOverride = override,
                        )
                    }
                    row.put(
                        "previewDetectMs",
                        (SystemClock.elapsedRealtimeNanos() - previewStarted) / 1_000_000.0,
                    )
                    preview.getOrNull()?.let { detectedPreview ->
                        row.put(
                            "preview",
                            JSONObject()
                                .put("hasQuad", detectedPreview.quad != null)
                                .put("confidence", detectedPreview.confidence.toDouble())
                                .put("method", detectedPreview.method)
                                .put("area", detectedPreview.quad?.area?.toDouble() ?: 0.0),
                        )
                    }
                }
                if (sheets < maxSheets) {
                    val sheetName = file.nameWithoutExtension + "-sheet.jpg"
                    val saved =
                        runCatching {
                                val sheet = contactSheet(bitmap, result?.quad, safetyQuad, row)
                                try {
                                    File(output, sheetName).outputStream().use {
                                        sheet.compress(Bitmap.CompressFormat.JPEG, 88, it)
                                    }
                                } finally {
                                    sheet.recycle()
                                }
                            }
                            .isSuccess
                    if (saved) {
                        sheets++
                        row.put("sheet", sheetName)
                    }
                }
            } finally {
                bitmap.recycle()
            }
            rows.put(row)
            writeReport(output, report)
        }
        report.put("sheets", sheets)
        report.put("finishedAtMillis", System.currentTimeMillis())
        writeReport(output, report)
        check(rows.length() > 0) { "No DocUNet photo was processed" }
    }

    private fun writeReport(output: File, report: JSONObject) {
        File(output, "results.json").writeText(report.toString(2))
    }

    private fun naturalKey(file: File): String =
        file.nameWithoutExtension.split('_').joinToString("/") { token ->
            token.toIntOrNull()?.let { "%04d".format(it) } ?: token
        }

    private fun quadJson(quad: Quad?): Any {
        if (quad == null) return JSONObject.NULL
        return JSONArray().apply {
            quad.points.forEach { point ->
                put(
                    JSONArray().apply {
                        put(point.x.toDouble())
                        put(point.y.toDouble())
                    }
                )
            }
        }
    }

    private class Panel(
        val bitmap: Bitmap,
        val frame: RectF,
        val originX: Float,
        val originY: Float,
        val originW: Float,
        val originH: Float,
    )

    private fun contactSheet(source: Bitmap, quad: Quad?, safety: Quad?, row: JSONObject): Bitmap {
        val size = 1200
        val half = size / 2
        val sheet = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val divider =
            Paint().apply {
                color = Color.rgb(206, 206, 206)
                strokeWidth = 2f
            }
        canvas.drawLine(half.toFloat(), 0f, half.toFloat(), size.toFloat(), divider)
        canvas.drawLine(0f, half.toFloat(), size.toFloat(), half.toFloat(), divider)
        val sourceCell = RectF(0f, 0f, half.toFloat(), half.toFloat())
        val detectedCell = RectF(half.toFloat(), 0f, size.toFloat(), half.toFloat())
        val detailCell = RectF(0f, half.toFloat(), half.toFloat(), size.toFloat())
        val metricsCell = RectF(half.toFloat(), half.toFloat(), size.toFloat(), size.toFloat())

        drawPanel(canvas, source, sourceCell, "SOURCE  red=detected  blue=crop safety")
        val detectedPanel = drawPanel(canvas, source, detectedCell, "DETECTED QUAD")
        if (quad != null) drawQuad(canvas, quad, detectedPanel, source.width, source.height)
        if (safety != null)
            drawQuad(canvas, safety, detectedPanel, source.width, source.height, safety = true)

        drawTag(canvas, detailCell, "CORNER DETAIL")
        val detail = quad?.let { zoomCrop(source, it) }
        if (detail == null) {
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(96, 96, 96)
                    textSize = 26f
                }
            canvas.drawText("no quad returned", detailCell.left + 40f, detailCell.centerY(), paint)
        } else {
            val panel =
                fitPanel(
                    detail.bitmap,
                    detailCell,
                    detail.left.toFloat(),
                    detail.top.toFloat(),
                    detail.bitmap.width.toFloat(),
                    detail.bitmap.height.toFloat(),
                )
            canvas.drawBitmap(detail.bitmap, null, panel.frame, filterPaint)
            detail.bitmap.recycle()
            if (quad != null) drawQuad(canvas, quad, panel, source.width, source.height)
            if (safety != null)
                drawQuad(canvas, safety, panel, source.width, source.height, safety = true)
        }

        drawTag(canvas, metricsCell, "METRICS")
        drawMetrics(canvas, metricsCell, row)
        return sheet
    }

    private fun drawPanel(canvas: Canvas, bitmap: Bitmap, cell: RectF, tag: String): Panel {
        val panel = fitPanel(bitmap, cell, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.drawBitmap(bitmap, null, panel.frame, filterPaint)
        drawTag(canvas, cell, tag)
        return panel
    }

    private fun fitPanel(
        bitmap: Bitmap,
        cell: RectF,
        originX: Float,
        originY: Float,
        originW: Float,
        originH: Float,
    ): Panel {
        val inset = 22f
        val header = 40f
        val availableW = cell.width() - inset * 2
        val availableH = cell.height() - header - inset
        val scale = min(availableW / bitmap.width, availableH / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = cell.left + (cell.width() - width) / 2
        val top = cell.top + header + (cell.height() - header - height) / 2
        return Panel(
            bitmap,
            RectF(left, top, left + width, top + height),
            originX,
            originY,
            originW,
            originH,
        )
    }

    private fun drawTag(canvas: Canvas, cell: RectF, text: String) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(44, 44, 44)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
        val width = paint.measureText(text) + 22f
        canvas.drawRect(
            cell.left + 8f,
            cell.top + 8f,
            cell.left + 8f + width,
            cell.top + 34f,
            Paint().apply { color = Color.rgb(236, 236, 236) },
        )
        canvas.drawText(text, cell.left + 19f, cell.top + 29f, paint)
    }

    private fun drawQuad(
        canvas: Canvas,
        quad: Quad,
        panel: Panel,
        sourceWidth: Int,
        sourceHeight: Int,
        safety: Boolean = false,
    ) {
        val mapped =
            quad.points.map { point ->
                PointF(
                    panel.frame.left +
                        (point.x * sourceWidth - panel.originX) / panel.originW *
                            panel.frame.width(),
                    panel.frame.top +
                        (point.y * sourceHeight - panel.originY) / panel.originH *
                            panel.frame.height(),
                )
            }
        val path =
            Path().apply {
                moveTo(mapped[0].x, mapped[0].y)
                for (index in 1 until mapped.size) lineTo(mapped[index].x, mapped[index].y)
                close()
            }
        if (safety) {
            canvas.drawPath(path, safetyStrokePaint)
        } else {
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
            mapped.forEach { canvas.drawCircle(it.x, it.y, 7f, cornerPaint) }
        }
    }

    private class Crop(val bitmap: Bitmap, val left: Int, val top: Int)

    private fun zoomCrop(source: Bitmap, quad: Quad): Crop? {
        val xs = quad.points.map { it.x * source.width }
        val ys = quad.points.map { it.y * source.height }
        val width = xs.max() - xs.min()
        val height = ys.max() - ys.min()
        if (width < 8f || height < 8f) return null
        val padX = width * .15f
        val padY = height * .15f
        val left = max(0f, xs.min() - padX).toInt()
        val top = max(0f, ys.min() - padY).toInt()
        val right = min(source.width.toFloat(), xs.max() + padX).toInt()
        val bottom = min(source.height.toFloat(), ys.max() + padY).toInt()
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth < 8 || cropHeight < 8) return null
        return Crop(Bitmap.createBitmap(source, left, top, cropWidth, cropHeight), left, top)
    }

    private fun drawMetrics(canvas: Canvas, cell: RectF, row: JSONObject) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(26, 26, 26)
                textSize = 23f
                typeface = Typeface.MONOSPACE
            }
        val lines =
            listOf(
                "file   " + row.optString("file"),
                "image  " + row.optInt("width") + " x " + row.optInt("height"),
                "quad   " + if (row.optBoolean("hasQuad")) "returned" else "none",
                "area   " + String.format(Locale.US, "%.2f%%", row.optDouble("area", 0.0) * 100),
                "aspect " + String.format(Locale.US, "%.2f", row.optDouble("aspect", 0.0)),
                "floor  " +
                    String.format(Locale.US, "%.0f%%", row.optDouble("areaFloor", 0.0) * 100),
                "arm    " + if (row.optBoolean("autoArm")) "yes" else "no",
                "conf   " + String.format(Locale.US, "%.3f", row.optDouble("confidence", 0.0)),
                "method " + row.optString("method", "-"),
                "detect " + String.format(Locale.US, "%.0f ms", row.optDouble("detectMs", 0.0)),
                "inner  " +
                    String.format(Locale.US, "%.0f ms", row.optDouble("detectorElapsedMs", 0.0)),
                "crop   " +
                    String.format(
                        Locale.US,
                        "%.0fx%.0f +%.1f%%",
                        row.optDouble("cropWidth", 0.0),
                        row.optDouble("cropHeight", 0.0),
                        row.optDouble("cropMargin", 0.0) * 100,
                    ),
            )
        var y = cell.top + 84f
        lines.forEach { line ->
            canvas.drawText(line, cell.left + 26f, y, paint)
            y += 36f
        }
    }
}
