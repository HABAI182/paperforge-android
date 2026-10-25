package cn.paperforge.app

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongImagesFlowTest {
    private fun reconstruct(zip: File): Pair<Bitmap, JSONObject> {
        ZipFile(zip).use { archive ->
            val report =
                JSONObject(
                    archive
                        .getInputStream(archive.getEntry("long-image-manifest.json"))
                        .bufferedReader()
                        .readText()
                )
            val width = report.getJSONObject("options").getInt("width")
            val result =
                Bitmap.createBitmap(
                    width,
                    report.getInt("totalLogicalRows"),
                    Bitmap.Config.ARGB_8888,
                )
            val canvas = Canvas(result)
            var y = 0
            val segments = report.getJSONArray("segments")
            for (i in 0 until segments.length()) {
                val segment = segments.getJSONObject(i)
                val image =
                    archive.getInputStream(archive.getEntry(segment.getString("file"))).use {
                        BitmapFactory.decodeStream(it)
                    }!!
                assertEquals(segment.getInt("height"), image.height)
                canvas.drawBitmap(image, 0f, y.toFloat(), null)
                y += image.height
                image.recycle()
            }
            assertEquals(result.height, y)
            return result to report
        }
    }

    private fun near(expected: Int, actual: Int) {
        assertTrue(
            "Expected $expected, got $actual",
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 45 &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) < 45 &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 45,
        )
    }

    @Test
    fun selectedPdfPagesAndSeparatorsRemainContinuousAcrossSegments() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val input = File(app.cacheDir, "long-pdf-source.pdf")
            val output = File(app.cacheDir, "long-pdf-proof.zip")
            PDDocument().use { pdf ->
                listOf(Color.RED, Color.GREEN, Color.BLUE).forEach { color ->
                    val page = PDPage(PDRectangle(100f, 300f))
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { c ->
                        c.setNonStrokingColor(
                            Color.red(color),
                            Color.green(color),
                            Color.blue(color),
                        )
                        c.addRect(0f, 0f, 100f, 300f)
                        c.fill()
                    }
                }
                pdf.save(input)
            }
            val hash = PdfAssembly.sha256(input)
            try {
                val options = LongImageOptions(128, 13, 256, Color.MAGENTA)
                val report = PdfLongImages.convert(input, output, "3,1", options)
                val (image, reloaded) = reconstruct(output)
                try {
                    assertEquals(781, image.height)
                    assertEquals(4, report.getJSONArray("segments").length())
                    near(Color.BLUE, image.getPixel(64, 383))
                    near(Color.MAGENTA, image.getPixel(64, 384))
                    near(Color.MAGENTA, image.getPixel(64, 396))
                    near(Color.RED, image.getPixel(64, 397))
                    near(Color.RED, image.getPixel(64, 780))
                    val pieces = reloaded.getJSONArray("sourceMapping")
                    assertEquals(
                        setOf(1, 3),
                        (0 until pieces.length())
                            .map { pieces.getJSONObject(it) }
                            .filterNot { it.optBoolean("separator") }
                            .map { it.getInt("sourceIndex") }
                            .toSet(),
                    )
                    assertEquals(hash, PdfAssembly.sha256(input))
                } finally {
                    image.recycle()
                }
            } finally {
                input.delete()
            }
        }

    @Test
    fun exifOrientationsAndTallImageRegionStripsKeepOrderAndLastRows() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val folder = File(app.cacheDir, "long-regions-${System.nanoTime()}").apply { mkdirs() }
            val output = File(app.cacheDir, "long-image-proof.zip")
            try {
                val source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(source)
                val paint = Paint()
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { i, c ->
                    paint.color = c
                    canvas.drawRect(
                        if (i % 2 == 0) 0f else 50f,
                        if (i < 2) 0f else 100f,
                        if (i % 2 == 0) 50f else 100f,
                        if (i < 2) 100f else 200f,
                        paint,
                    )
                }
                val inputs =
                    (1..8).map { orientation ->
                        File(folder, "orientation-$orientation.jpg").also { file ->
                            file.outputStream().use {
                                source.compress(Bitmap.CompressFormat.JPEG, 100, it)
                            }
                            ExifInterface(file).apply {
                                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                                saveAttributes()
                            }
                        }
                    }
                source.recycle()
                val tall = File(folder, "tall.png")
                Bitmap.createBitmap(300, 12000, Bitmap.Config.ARGB_8888).let { image ->
                    val c = Canvas(image)
                    listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { index, color ->
                        paint.color = color
                        c.drawRect(0f, index * 4000f, 300f, (index + 1) * 4000f, paint)
                    }
                    tall.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                val files = inputs + tall
                val hashes = files.map { PdfAssembly.sha256(it) }
                ImageLongImages.convert(
                    files,
                    files.map { it.name },
                    output,
                    LongImageOptions(128, 7, 256, Color.WHITE),
                )
                val (result, report) = reconstruct(output)
                try {
                    val expected =
                        listOf(
                            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
                            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
                            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
                            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
                            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
                            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
                        )
                    var y = 0
                    for (i in 0..7) {
                        val height = if (i < 4) 256 else 64
                        near(expected[i][0], result.getPixel(32, y + height / 4))
                        near(expected[i][1], result.getPixel(96, y + height / 4))
                        near(expected[i][2], result.getPixel(32, y + height * 3 / 4))
                        near(expected[i][3], result.getPixel(96, y + height * 3 / 4))
                        near(Color.WHITE, result.getPixel(64, y + height + 3))
                        y += height + 7
                    }
                    assertEquals(y + 5120, result.height)
                    near(Color.RED, result.getPixel(64, y + 1))
                    near(Color.GREEN, result.getPixel(64, y + 2560))
                    near(Color.BLUE, result.getPixel(64, result.height - 1))
                    assertEquals(hashes, files.map { PdfAssembly.sha256(it) })
                    assertEquals(result.height.toLong(), report.getLong("totalLogicalRows"))
                } finally {
                    result.recycle()
                }
            } finally {
                folder.listFiles()?.forEach { it.delete() }
                folder.delete()
            }
        }
}
