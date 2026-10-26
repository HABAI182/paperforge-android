package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExportPresetsFlowTest {
    @Test
    fun portablePresetProducesNamedDpiImagesAndPdfWithoutChangingOtherSettings() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val settings = UserSettings.exportJson()
        val profiles = SettingsProfiles(app)
        var preset: SettingsProfile? = null
        try {
            val doc = repo.newScan()
            repo.rename(doc, "导出预设验收")
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                val source = File(repo.folder(doc.id), "preset-source-$index.png")
                Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888).let { image ->
                    image.eraseColor(color)
                    Canvas(image)
                        .drawText(
                            "EXPORT ${index+1}",
                            20f,
                            70f,
                            Paint().apply {
                                this.color = Color.BLACK
                                textSize = 30f
                            },
                        )
                    source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                repo.dao.putPage(
                    PageRow(
                        UUID.randomUUID().toString(),
                        doc.id,
                        index,
                        source.path,
                        corners = Quad.Full.encode(),
                        filter = "ORIGINAL",
                    )
                )
            }
            UserSettings.set("theme", "DARK")
            val selected =
                ScanExportOptions("A4", "CUSTOM", 72, 84, false, "PNG", "{title}_{pages}_{date}")
            selected.remember()
            preset = profiles.save("分享页面 ${System.nanoTime()}", setOf("export"))
            ScanExportOptions().remember()
            profiles.apply(preset!!)
            val restored = ScanExportOptions.current()
            assertEquals(selected, restored)
            assertEquals("DARK", UserSettings.string("theme", "SYSTEM"))
            suspend fun export(options: ScanExportOptions): File {
                val work =
                    DocumentJobs.enqueue(
                        repo,
                        "SCAN_EXPORT",
                        JobInput.from(
                            workDataOf(
                                "document" to doc.id,
                                "title" to "导出预设验收",
                                "paper" to options.paper,
                                "quality" to options.quality,
                                "searchable" to false,
                                "format" to options.format,
                                "resolution" to options.effectiveResolution,
                                "fileName" to options.fileName("导出预设验收", 2),
                            )
                        ),
                    )
                val info =
                    withTimeout(60000) {
                        WorkManager.getInstance(app)
                            .getWorkInfoByIdFlow(work)
                            .filterNotNull()
                            .first { it.state.isFinished }
                    }
                assertEquals(
                    info.outputData.getString("error"),
                    WorkInfo.State.SUCCEEDED,
                    info.state,
                )
                return File(info.outputData.getString("path")!!)
            }
            val images = export(restored)
            assertTrue(images.name.startsWith("导出预设验收_2_"))
            ZipFile(images).use { zip ->
                assertEquals(2, zip.size())
                listOf("page-0001.png", "page-0002.png").forEachIndexed { index, name ->
                    val image =
                        zip.getInputStream(zip.getEntry(name)).use {
                            BitmapFactory.decodeStream(it)
                        }
                    assertTrue(image.width in 594..596)
                    assertTrue(image.height in 841..843)
                    val center = image.getPixel(image.width / 2, image.height / 2)
                    if (index == 0) assertTrue(Color.red(center) > 240 && Color.blue(center) < 15)
                    else assertTrue(Color.blue(center) > 240 && Color.red(center) < 15)
                    image.recycle()
                }
            }
            val pdf = export(restored.copy(format = "PDF"))
            PDDocument.load(pdf).use { assertEquals(2, it.numberOfPages) }
            File(app.cacheDir, "export-presets-proof.json")
                .writeText(
                    JSONObject()
                        .put("images", images.path)
                        .put("pdf", pdf.path)
                        .put("document", doc.id)
                        .put("presetRoundTrip", true)
                        .put("dpi", 72)
                        .put("pages", 2)
                        .toString(2)
                )
        } finally {
            preset?.let(profiles::remove)
            UserSettings.importJson(settings.toString())
            UserSettings.prefs.edit().commit()
        }
    }
}
