package cn.paperforge.app

import android.graphics.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.workDataOf
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class ResourceSettingsFlowTest {
    @Test
    fun typedConfigurationQueuePriorityAndActualLargePageBuffers() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val proof = File(app.filesDir, "resource-settings-proof").apply { mkdirs() }
            val values =
                JSONObject(SettingKeys.defaults)
                    .put("reduceMotion", true)
                    .put("haptics", false)
                    .put("documentSidebar", false)
                    .put("readerSelectionOrder", "5")
                    .put("ocrOrder", "VERTICAL_LTR")
            val normalized = UserSettings.normalizedValues(values)
            assertEquals(SettingKeys.all.size, normalized.length())
            assertTrue(normalized.getBoolean("reduceMotion"))
            assertFalse(normalized.getBoolean("haptics"))
            assertFalse(normalized.getBoolean("documentSidebar"))
            assertEquals("5", normalized.getString("readerSelectionOrder"))
            assertEquals("VERTICAL_LTR", normalized.getString("ocrOrder"))
            val prefs = app.getSharedPreferences("resource-settings-test-only", 0)
            SettingKeys.ReduceMotion.write(prefs.edit(), normalized.getBoolean("reduceMotion"))
                .commit()
            SettingKeys.ReaderSelectionOrder.write(
                    prefs.edit(),
                    normalized.getString("readerSelectionOrder"),
                )
                .commit()
            assertTrue(SettingKeys.ReduceMotion.read(prefs))
            assertEquals("5", SettingKeys.ReaderSelectionOrder.read(prefs))
            prefs.edit().clear().commit()

            val gate = InteractiveGate()
            val order = mutableListOf<String>()
            gate.acquire("active")
            val jobs =
                listOf("queued", "preferred", "visible").map { id ->
                    launch {
                        gate.acquire(id, interactive = id == "visible")
                        order += id
                        gate.release()
                    }
                }
            gate.waitingIds.first { it.size == 3 }
            gate.prefer("preferred")
            gate.release()
            jobs.joinAll()
            assertEquals(listOf("visible", "preferred", "queued"), order)
            gate.acquire("active")
            val cancelled = launch { gate.acquire("cancelled") }
            gate.waitingIds.first { it.contains("cancelled") }
            cancelled.cancelAndJoin()
            gate.release()
            gate.use(true) { assertEquals(emptyList<String>(), gate.waitingIds.value) }

            val database = Room.inMemoryDatabaseBuilder(app, PaperDatabase::class.java).build()
            val repo = Repository(app, database)
            try {
                val source = File(proof, "source.pdf")
                PDDocument().use { pdf ->
                    val page = PDPage(PDRectangle(600f, 800f))
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { c ->
                        c.setNonStrokingColor(1f, 0f, 0f)
                        c.addRect(0f, 700f, 600f, 100f)
                        c.fill()
                        c.setNonStrokingColor(0f, 0f, 1f)
                        c.addRect(0f, 0f, 600f, 100f)
                        c.fill()
                    }
                    pdf.save(source)
                }
                val row =
                    DocumentJobs.prepare(
                        repo,
                        "PDF",
                        JobInput(JSONObject().put("mode", "flatten").put("memoryBudgetMb", 32)),
                    )
                assertEquals(
                    32,
                    JSONObject(row.payload).getJSONObject("input").getInt("memoryBudgetMb"),
                )
                val job = JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input")))
                val result = File(proof, "bounded.pdf")
                var observed: JSONObject? = null
                val priorityBefore =
                    android.os.Process.getThreadPriority(android.os.Process.myTid())
                withContext(DocumentJobs.memoryContext(repo, workDataOf("task" to row.id))) {
                    assertEquals(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND,
                        android.os.Process.getThreadPriority(android.os.Process.myTid()),
                    )
                    val plan = RuntimeBudget.memoryPlan()
                    assertEquals(32, plan.requestedMb)
                    RasterPageStages.convert(
                        source,
                        result,
                        JobFileStages(repo, job),
                        "memory-proof",
                        12000,
                        encode = { pdf, bitmap ->
                            assertTrue(bitmap.width.toLong() * bitmap.height <= plan.pixels())
                            assertTrue(bitmap.height < 12000)
                            assertTrue(Color.red(bitmap.getPixel(bitmap.width / 2, 10)) > 200)
                            assertTrue(
                                Color.blue(bitmap.getPixel(bitmap.width / 2, bitmap.height - 11)) >
                                    200
                            )
                            observed =
                                JSONObject()
                                    .put("width", bitmap.width)
                                    .put("height", bitmap.height)
                                    .put("workingBudgetBytes", plan.bytes)
                                    .put("pixelLimit", plan.pixels())
                                    .put("requestedEdge", 12000)
                                    .put("completeFrame", true)
                            PdfImageEncoder.jpeg(pdf, bitmap, false, 70, 72)
                        },
                    )
                }
                assertEquals(
                    priorityBefore,
                    android.os.Process.getThreadPriority(android.os.Process.myTid()),
                )
                PDDocument.load(result).use { pdf ->
                    val page = pdf.getPage(0)
                    assertEquals(600f, page.mediaBox.width, 0f)
                    assertEquals(800f, page.mediaBox.height, 0f)
                    val image =
                        page.resources.xObjectNames
                            .map { page.resources.getXObject(it) }
                            .filterIsInstance<PDImageXObject>()
                            .single()
                    assertEquals(observed!!.getInt("width"), image.width)
                    assertEquals(observed!!.getInt("height"), image.height)
                }
                File(proof, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("typedKeys", SettingKeys.all.size)
                            .put("normalizedKeys", normalized.length())
                            .put("flagsAndVerticalOrderRoundTrip", true)
                            .put("priorityOrder", JSONArray(order))
                            .put("cancelledWaiterReleased", true)
                            .put("imageWorkingSet", observed)
                            .put("originalPaperSizeKept", true)
                            .put("mainPreferencesUntouched", true)
                            .put("backgroundThreadPriorityRestored", true)
                            .put("opencvThreads", org.opencv.core.Core.getNumThreads())
                            .toString(2)
                    )
            } finally {
                database.close()
            }
        }
}
