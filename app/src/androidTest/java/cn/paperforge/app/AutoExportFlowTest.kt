package cn.paperforge.app

import android.graphics.*
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class AutoExportFlowTest {
    @Test
    fun conditionTriggerDeduplicatesFailureAndManualRetryReusesScan() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val before = UserSettings.string("autoExportRules", "[]")
        try {
            val collection = "AutoAcceptance-${UUID.randomUUID()}"
            suspend fun scan(title: String, tags: String): Pair<DocumentRow, File> {
                val original = repo.newScan()
                val doc = original.copy(title = title, folder = "$collection/child", tags = tags)
                repo.dao.putDocument(doc)
                val image = File(repo.folder(doc.id), "auto-original.png")
                Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).let { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    Canvas(bitmap)
                        .drawText(
                            "AUTO 00123",
                            50f,
                            220f,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.BLACK
                                textSize = 50f
                            },
                        )
                    image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                repo.dao.putPage(
                    PageRow(
                        UUID.randomUUID().toString(),
                        doc.id,
                        0,
                        image.path,
                        filter = "ORIGINAL",
                    )
                )
                return doc to image
            }
            val matching = scan("自动导出验收", "archive")
            val other = scan("不匹配自动规则", "other")
            val hash = PdfAssembly.sha256(matching.second)
            var recipe =
                WorkflowRecipe(
                    name = "自动归档验收",
                    steps =
                        listOf(
                            RecipeStep(
                                operation = "rename",
                                options = JSONObject().put("template", "{title}_auto"),
                            ),
                            RecipeStep(
                                operation = "save",
                                options = JSONObject().put("destination", "unavailable-auto-test"),
                            ),
                        ),
                )
            val rule =
                AutoExportRule(
                    name = "自动导出验收",
                    enabled = true,
                    folder = collection,
                    tags = "archive",
                    recipe = recipe,
                    export =
                        JSONObject()
                            .put("searchable", false)
                            .put("paper", "A4")
                            .put("resolution", "STANDARD")
                            .put("quality", 90),
                    delaySeconds = 0,
                )
            AutoExportRules.save(rule)
            var row =
                withTimeout(15000) {
                        repo.dao.observeTasks().first { rows ->
                            rows.any {
                                it.type == "AUTO_EXPORT" &&
                                    JSONObject(it.payload)
                                        .getJSONObject("input")
                                        .optString("automationRule") == rule.id
                            }
                        }
                    }
                    .first {
                        it.type == "AUTO_EXPORT" &&
                            JSONObject(it.payload)
                                .getJSONObject("input")
                                .optString("automationRule") == rule.id
                    }
            suspend fun finished(id: UUID) =
                withTimeout(60000) {
                    WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                        it.state.isFinished
                    }
                }
            val first = finished(UUID.fromString(row.workId))
            assertEquals(WorkInfo.State.FAILED, first.state)
            row = checkNotNull(repo.dao.task(row.id))
            assertEquals(matching.first.id, row.documentId)
            assertTrue(first.outputData.getString("error")!!.contains("步骤 2"))
            val base = File(row.resourceEntries().first { it.id == "auto-source-pdf" }.path)
            val baseHash = PdfAssembly.sha256(base)
            val stamp = base.lastModified()
            val signals = AutoExportCoordinator.signals(repo).first()
            val signal = signals.first { it.document.id == matching.first.id }
            assertNull(AutoExportCoordinator.enqueue(repo, rule, signal))
            assertEquals(
                1,
                repo.dao.allTasks().count {
                    it.type == "AUTO_EXPORT" &&
                        JSONObject(it.payload).getJSONObject("input").optString("automationRule") ==
                            rule.id
                },
            )
            assertFalse(rule.matches(other.first))
            recipe = recipe.copy(steps = recipe.steps.filterNot { it.operation == "save" })
            val updated =
                repo.database.withTransaction {
                    val payload = JSONObject(row.payload)
                    val input = payload.getJSONObject("input")
                    input
                        .put("manual", true)
                        .put("delaySeconds", 0)
                        .put("options", JSONObject().put("recipe", recipe.json()).toString())
                    input.getJSONObject("autoRule").put("recipe", recipe.json())
                    row.copy(payload = payload.toString()).also { repo.dao.putTask(it) }
                }
            val next = finished(DocumentJobs.retry(repo, updated))
            assertEquals(next.outputData.getString("error"), WorkInfo.State.SUCCEEDED, next.state)
            val output = File(next.outputData.getString("path")!!)
            PDDocument.load(output).use { assertEquals(1, it.numberOfPages) }
            assertEquals(baseHash, PdfAssembly.sha256(base))
            assertEquals(stamp, base.lastModified())
            assertEquals(hash, PdfAssembly.sha256(matching.second))
            assertNotNull(repo.dao.output(output.path))
            assertNull(repo.dao.documentByPath(output.path))
            assertNull(AutoExportCoordinator.enqueue(repo, rule, signal))
            val preview =
                repo.registerPdf(output, "自动结果预览").copy(folder = collection, tags = "archive")
            repo.dao.putDocument(preview)
            val previewSignal =
                AutoExportCoordinator.signals(repo).first().first { it.document.id == preview.id }
            assertTrue(rule.matches(preview))
            assertNull(AutoExportCoordinator.enqueue(repo, rule, previewSignal))
            File(app.cacheDir, "auto-export-proof.json")
                .writeText(
                    JSONObject()
                        .put("task", row.id)
                        .put("rule", rule.json())
                        .put("document", matching.first.id)
                        .put("output", output.path)
                        .put("sourceSha256", hash)
                        .put("baseReused", true)
                        .put("deduplicated", true)
                        .put("otherExcluded", true)
                        .put("previewDoesNotReenter", true)
                        .toString(2)
                )
        } finally {
            UserSettings.set("autoExportRules", before)
            UserSettings.prefs.edit().commit()
        }
    }
}
