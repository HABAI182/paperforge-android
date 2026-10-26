package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.PdfAssembly
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual emulator camera JPEGs with independent paper bounds, not a hand-authored EXIF model. */
class CaptureGeometryFlowTest {
    @Test
    fun cameraJpegsKeepFourMarkersAcrossRotationCropAndRender() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val folder = File(app.cacheDir, "camera-geometry-proof").apply { mkdirs() }
        val fixtures =
            JSONObject(assets.open("camera-geometry/fixtures.json").bufferedReader().readText())
                .getJSONArray("captures")
        val proof = JSONArray()
        val orientations = mutableSetOf<Int>()
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val source = File(folder, "$index.jpg")
            assets.open("camera-geometry/${fixture.getString("file")}").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val hash = PdfAssembly.sha256(source)
            val orientation =
                ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            orientations.add(orientation)
            assertEquals(fixture.getInt("orientation"), orientation)
            val decoded = ScanProcessor.decode(source, 4000)
            assertEquals(fixture.getInt("width"), decoded.width)
            assertEquals(fixture.getInt("height"), decoded.height)
            decoded.recycle()
            val r = fixture.getJSONObject("reference")
            fun values(key: String) =
                r.getJSONArray(key).let { a ->
                    (0 until a.length()).map { a.getDouble(it).toFloat() }
                }
            val reference =
                CaptureReference(
                    Quad.decode(r.getString("quad")),
                    r.getLong("analyzedAt"),
                    r.getLong("requestedAt"),
                    r.getInt("width"),
                    r.getInt("height"),
                    r.getString("viewport"),
                    r.getLong("sourceTimestampNs"),
                    r.getDouble("confidence").toFloat(),
                    values("scene"),
                    values("paper"),
                    r.optDouble("motion").toFloat(),
                )
            val result = ScanProcessor.detectFile(source, reference.quad, reference, "HYBRID", true)
            val quad = requireNotNull(result.quad)
            val box = fixture.getJSONArray("paperBox")
            val expected =
                listOf(
                    PointN(box.getDouble(0).toFloat(), box.getDouble(1).toFloat()),
                    PointN(box.getDouble(2).toFloat(), box.getDouble(1).toFloat()),
                    PointN(box.getDouble(2).toFloat(), box.getDouble(3).toFloat()),
                    PointN(box.getDouble(0).toFloat(), box.getDouble(3).toFloat()),
                )
            var maxError = 0f
            quad.points.zip(expected).forEach { (actual, expectedPoint) ->
                val error =
                    kotlin.math.hypot(actual.x - expectedPoint.x, actual.y - expectedPoint.y)
                maxError = maxOf(maxError, error)
                assertTrue("rotation $index crop corner error $error", error < .015f)
            }
            val rendered =
                ScanProcessor.render(
                    PageRow(
                        "geometry-$index",
                        "fixture",
                        index,
                        source.path,
                        corners = quad.encode(),
                        filter = "ORIGINAL",
                    ),
                    1200,
                )
            val counts = IntArray(4)
            for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                val pixel = rendered.getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                when {
                    red > 150 && green < 90 && blue < 90 -> counts[0]++
                    green > 70 && red < 80 && blue < 80 -> counts[1]++
                    blue > 150 && red < 90 && green < 90 -> counts[2]++
                    red > 150 && green in 100..220 && blue < 80 -> counts[3]++
                }
            }
            assertTrue("four corner markers remain ${counts.toList()}", counts.all { it > 100 })
            File(folder, "render-$index.png").outputStream().use {
                rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            proof.put(
                JSONObject()
                    .put("rotation", index)
                    .put("exif", orientation)
                    .put("sourceSha256", hash)
                    .put("method", result.method)
                    .put("maxNormalizedCornerError", maxError)
                    .put("markerPixels", JSONArray(counts.toList()))
                    .put("renderWidth", rendered.width)
                    .put("renderHeight", rendered.height)
            )
            rendered.recycle()
            assertEquals(hash, PdfAssembly.sha256(source))
        }
        assertEquals(setOf(1, 3, 6, 8), orientations)
        File(folder, "proof.json").writeText(proof.toString(2))
    }
}
