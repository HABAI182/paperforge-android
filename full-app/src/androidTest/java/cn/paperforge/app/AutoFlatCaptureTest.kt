package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutoFlatCaptureTest {
    @Test
    fun capturedPagePublishesFlatAppearanceWithOriginalAndUndo() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val doc = repo.newScan()
        val oldFilter = UserSettings[SettingKeys.Filter]
        val oldAi = UserSettings[SettingKeys.ScanAiFinish]
        val oldLock = UserSettings[SettingKeys.LockCrop]
        try {
            UserSettings.set(SettingKeys.Filter, "WORKBOOK")
            UserSettings.set(SettingKeys.ScanAiFinish, false)
            UserSettings.set(SettingKeys.LockCrop, false)
            val file = repo.captureFile(doc.id)
            Bitmap.createBitmap(700, 950, Bitmap.Config.ARGB_8888).also { image ->
                Canvas(image).apply {
                    drawColor(Color.rgb(55, 48, 43))
                    drawRect(60f, 50f, 640f, 895f, Paint().apply { color = Color.WHITE })
                    val pen =
                        Paint().apply {
                            color = Color.DKGRAY
                            strokeWidth = 2f
                            textSize = 24f
                        }
                    for (y in 110..850 step 45) drawLine(70f, y.toFloat(), 630f, y.toFloat(), pen)
                    drawText("PaperForge", 100f, 240f, pen)
                }
                file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 97, it) }
                image.recycle()
            }
            val task = CaptureTasks.prepare(repo, doc.id, null, file = file)
            CaptureTasks.accept(repo, task.id)
            withTimeout(45000) {
                while (repo.dao.task(task.id)!!.state in LocalTaskRow.active) delay(100)
            }
            val completed = repo.dao.task(task.id)!!
            assertEquals(completed.error, LocalTaskState.SUCCEEDED, completed.state)
            val page = repo.dao.pages(doc.id).single()
            assertEquals("flat-document", page.detectionMethod)
            assertEquals("ORIGINAL", page.filter)
            assertFalse(
                JSONObject(page.processingMetadata).getJSONObject("flatDocument").getBoolean("ai")
            )
            assertTrue(File(page.original).isFile)
            assertTrue(
                PageResources.entries(page).any {
                    it.role == "source-original" && it.path == file.path
                }
            )
            assertTrue(
                PageResources.entries(page).any { it.role == "dewarp-grid" && File(it.path).isFile }
            )
            repo.undo(doc.id)
            assertEquals(file.path, repo.dao.pages(doc.id).single().original)
            assertTrue(file.isFile)
        } finally {
            UserSettings.set(SettingKeys.Filter, oldFilter)
            UserSettings.set(SettingKeys.ScanAiFinish, oldAi)
            UserSettings.set(SettingKeys.LockCrop, oldLock)
            repo.deleteDocument(doc)
        }
    }
}
