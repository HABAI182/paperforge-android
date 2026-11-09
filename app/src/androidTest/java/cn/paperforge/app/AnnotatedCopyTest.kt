package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether pen marks saved as PDF ink annotations show when the reader draws the pages. Runs on any PDFs in
 * files/annot-samples and writes red-pixel counts per page and render mode to files/phone-out/annot/annot.json.
 */
class AnnotatedCopyTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun inkAnnotationsShowInTheReader() {
        val samples = File(context.filesDir, "annot-samples").listFiles().orEmpty().filter { it.extension == "pdf" }
        val out = File(context.filesDir, "phone-out/annot").apply { mkdirs() }
        val rows = JSONArray()
        for (file in samples) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        val row = JSONObject().put("file", file.name).put("page", i + 1)
                        for ((name, mode) in listOf("display" to PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY, "print" to PdfRenderer.Page.RENDER_MODE_FOR_PRINT)) {
                            renderer.openPage(i).use { page ->
                                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, mode)
                                val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                                row.put(name, pixels.count { Color.red(it) > 180 && Color.green(it) < 90 && Color.blue(it) < 90 })
                                bitmap.recycle()
                            }
                        }
                        rows.put(row)
                    }
                }
            }
            // The reader's own page drawing, which adds the marks.
            cn.paperforge.app.ui.PdfSession(file).use { session ->
                for (i in 0 until session.count) {
                    val bitmap = session.render(i, 1600)
                    val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                    rows.getJSONObject(rows.length() - session.count + i)
                        .put("reader", pixels.count { Color.red(it) > 180 && Color.green(it) < 90 && Color.blue(it) < 90 })
                    File(out, "${file.nameWithoutExtension}-reader-${i + 1}.jpg").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
                    }
                }
            }
        }
        File(out, "annot.json").writeText(rows.toString(1))
        assertTrue("no PDFs in files/annot-samples", samples.isNotEmpty())
    }
}
