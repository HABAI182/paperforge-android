package cn.paperforge.app

import android.graphics.*
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.OcrLayout
import cn.paperforge.app.ocr.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class OcrScriptsFlowTest {
    @Test
    fun importedOfficialScriptsKeepRealRecognitionAndSearchableUnicode() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val router = app.ocrEngine as RoutingOcrEngine
            val folder =
                File(app.filesDir, "ocr-scripts-proof-${UUID.randomUUID()}").apply { mkdirs() }
            val oldBackend = UserSettings.string("ocrBackend", "MLKIT")
            val oldPack = UserSettings.string("ocrPackId", "")
            val oldMode = UserSettings.string("ocrMode", "BALANCED")
            val oldOrder = UserSettings.string("ocrOrder", "AUTO")
            val samples = JSONArray()
            try {
                UserSettings.set("ocrMode", "FAST")
                UserSettings.set("ocrOrder", "AUTO")
                val definitions =
                    listOf(
                        Triple("mobile", "日本語の文書を読み取ります", "ja"),
                        Triple("korean", "문서를 읽고 원본을 보관합니다", "ko"),
                        Triple("devanagari", "यह एक हिंदी दस्तावेज है", "hi"),
                        Triple("latin", "Český dokument číslo 00123", "cs"),
                    )
                for ((name, truth, script) in definitions) {
                    val only = InstrumentationRegistry.getArguments().getString("scripts")
                    if (only != null && name !in only.split(',')) continue
                    val pack =
                        router.packs.install(
                            Uri.fromFile(
                                File(app.getExternalFilesDir(null), "paddle-v5-$name.pfocr.zip")
                            )
                        )
                    assertTrue(pack.languages.contains(script))
                    UserSettings.set("ocrBackend", "PADDLE")
                    UserSettings.set("ocrPackId", pack.id)
                    val frozen = OcrRequest.current(router.id).bind(router)
                    assertEquals(pack.fontFiles, frozen.fontFiles)
                    val bitmap = Bitmap.createBitmap(1800, 320, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 70f
                            typeface =
                                pack.fontFiles.firstOrNull()?.let { Typeface.createFromFile(it) }
                                    ?: if (script == "ja")
                                        Typeface.createFromAsset(
                                            app.assets,
                                            "fonts/PaperForgeCJK-Regular.ttf",
                                        )
                                    else Typeface.DEFAULT
                        }
                    Canvas(bitmap).drawText(truth, 80f, 180f, paint)
                    File(folder, "$name.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val source = File(folder, "$name-source.pdf")
                    PDDocument().use { pdf ->
                        val page = PDPage(PDRectangle(1800f, 320f))
                        pdf.addPage(page)
                        val image = LosslessFactory.createFromImage(pdf, bitmap)
                        PDPageContentStream(pdf, page).use {
                            it.drawImage(image, 0f, 0f, 1800f, 320f)
                        }
                        pdf.save(source)
                    }
                    bitmap.recycle()
                    val sourceHash = PdfAssembly.sha256(source)
                    var captured: OcrLayout? = null
                    var calls = 0
                    val recording =
                        object : OcrEngine {
                            override val id = frozen.id
                            override val fontFiles
                                get() = frozen.fontFiles

                            override suspend fun layout(
                                page: PageRow,
                                chinese: Boolean,
                                options: OcrOptions,
                            ) = frozen.layout(page, chinese, options)

                            override suspend fun recognizeBitmap(
                                source: Bitmap,
                                chinese: Boolean,
                                options: OcrOptions,
                            ): OcrLayout {
                                calls++
                                return frozen.recognizeBitmap(source, chinese, options).also {
                                    captured = it
                                }
                            }
                        }
                    val output = File(folder, "$name-searchable.pdf")
                    val report = PdfSearchable.convert(app, source, output, recording)
                    val result = checkNotNull(captured)
                    assertEquals(1, calls)
                    assertTrue("Real $name model returned no text", result.lines.isNotEmpty())
                    val copied = ReaderDocument.text(output, 0).joinToString("\n") { it.text }
                    fun compact(value: String) =
                        Normalizer.normalize(value, Normalizer.Form.NFC).replace(Regex("\\s+"), "")
                    assertEquals(
                        "Actual $name recognition must survive the text layer",
                        compact(result.text),
                        compact(copied),
                    )
                    assertFalse(copied.contains('?'))
                    assertTrue(PdfSearch.find(output, result.lines.first().text).isNotEmpty())
                    assertEquals(sourceHash, PdfAssembly.sha256(source))
                    samples.put(
                        JSONObject()
                            .put("sample", name)
                            .put("truth", truth)
                            .put("recognized", result.text)
                            .put("copied", copied)
                            .put("engine", frozen.id)
                            .put("sourceSha256", sourceHash)
                            .put("sourceUnchanged", true)
                            .put("fonts", JSONArray(pack.fontFiles.map { OcrModelPacks.hash(it) }))
                            .put("layout", JSONObject(result.encode()))
                            .put("report", report)
                    )
                    File(folder, "proof.json")
                        .writeText(JSONObject().put("samples", samples).toString(2))
                    android.util.Log.i(
                        "OcrScriptsFlowTest",
                        "Sample $name: ${result.text}; proof=${folder.path}",
                    )
                }
            } finally {
                UserSettings.set("ocrBackend", oldBackend)
                UserSettings.set("ocrPackId", oldPack)
                UserSettings.set("ocrMode", oldMode)
                UserSettings.set("ocrOrder", oldOrder)
                router.trimUnused()
            }
        }
}
