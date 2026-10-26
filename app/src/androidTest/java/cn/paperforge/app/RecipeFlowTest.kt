package cn.paperforge.app

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import cn.paperforge.app.pdf.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class RecipeFlowTest {
    @Test
    fun changedTemplateKeepsPreviouslyPublishedResult() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val source = File(app.filesDir, "recipe-version-source.pdf")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("phone-office-table.pdf")
            .use { input -> source.outputStream().use { input.copyTo(it) } }
        var recipe =
            WorkflowRecipe(
                name = "配方结果版本验收",
                steps =
                    listOf(
                        RecipeStep(
                            operation = "rename",
                            options = JSONObject().put("template", "{title}_one"),
                        )
                    ),
            )
        val work =
            DocumentJobs.enqueue(
                repo,
                "CONVERT",
                JobInput(
                    JSONObject()
                        .put("kind", "RECIPE")
                        .put("name", "版本保留.pdf")
                        .put("inputs", JSONArray(listOf(source.path)))
                        .put("names", JSONArray(listOf("版本保留.pdf")))
                        .put("options", JSONObject().put("recipe", recipe.json()).toString())
                ),
            )
        suspend fun result(id: java.util.UUID): WorkInfo =
            withTimeout(60000) {
                WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                    it.state.isFinished
                }
            }
        val first = result(work)
        assertEquals(first.outputData.getString("error"), WorkInfo.State.SUCCEEDED, first.state)
        val original = File(first.outputData.getString("path")!!)
        val oldHash = PdfAssembly.sha256(original)
        assertNotNull(repo.dao.documentByPath(original.path))
        val row = checkNotNull(repo.dao.task(first.outputData.getString("taskId")!!))
        recipe =
            recipe.copy(
                steps =
                    recipe.steps.map {
                        it.copy(options = JSONObject().put("template", "{title}_two"))
                    }
            )
        val updated =
            repo.database.withTransaction {
                val payload = JSONObject(row.payload)
                payload
                    .getJSONObject("input")
                    .put("options", JSONObject().put("recipe", recipe.json()).toString())
                row.copy(payload = payload.toString()).also { repo.dao.putTask(it) }
            }
        val second = result(DocumentJobs.retry(repo, updated))
        assertEquals(second.outputData.getString("error"), WorkInfo.State.SUCCEEDED, second.state)
        val next = File(second.outputData.getString("path")!!)
        assertNotEquals(original.parentFile, next.parentFile)
        assertTrue(original.name.endsWith("_one.pdf"))
        assertTrue(next.name.endsWith("_two.pdf"))
        assertEquals(oldHash, PdfAssembly.sha256(original))
        assertEquals(oldHash, PdfAssembly.sha256(next))
        assertNotNull(repo.dao.documentByPath(original.path))
        assertNotNull(repo.dao.documentByPath(next.path))
        File(app.cacheDir, "recipe-version-proof.json")
            .writeText(
                JSONObject()
                    .put("task", row.id)
                    .put("old", original.path)
                    .put("new", next.path)
                    .put("sha256", oldHash)
                    .put("oldResultRetained", true)
                    .toString(2)
            )
    }

    @Test
    fun saveRetargetReusesOcrCompressionAndNamingStages() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val before = UserSettings.string("workflowRecipes", "[]")
        try {
            val source = File(app.filesDir, "recipe-scanned-input.pdf")
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("phone-scanned-table.pdf")
                .use { input -> source.outputStream().use { input.copyTo(it) } }
            val hash = PdfAssembly.sha256(source)
            var recipe =
                WorkflowRecipe(
                    name = "配方续跑验收",
                    steps =
                        listOf(
                            RecipeStep(operation = "searchable"),
                            RecipeStep(
                                operation = "compress",
                                options = JSONObject().put("quality", 84),
                            ),
                            RecipeStep(
                                operation = "rename",
                                options = JSONObject().put("template", "{title}_{pages}"),
                            ),
                            RecipeStep(
                                operation = "save",
                                options = JSONObject().put("destination", "unavailable-before"),
                            ),
                        ),
                )
            WorkflowRecipes.save(recipe)
            val exported = UserSettings.exportJson()
            UserSettings.set("workflowRecipes", "[]")
            UserSettings.importJson(exported)
            assertEquals(
                recipe.json().toString(),
                WorkflowRecipes.all().first { it.id == recipe.id }.json().toString(),
            )
            val work =
                DocumentJobs.enqueue(
                    repo,
                    "CONVERT",
                    JobInput(
                        JSONObject()
                            .put("kind", "RECIPE")
                            .put("name", "扫描配方验收.pdf")
                            .put("inputs", JSONArray(listOf(source.path)))
                            .put("names", JSONArray(listOf("扫描配方验收.pdf")))
                            .put("options", JSONObject().put("recipe", recipe.json()).toString())
                            .put(
                                "ocrRequest",
                                OcrRequest(
                                        BundledOcrEngine.ID,
                                        true,
                                        OcrOptions(OcrMode.FAST, false),
                                    )
                                    .json(),
                            )
                    ),
                )
            suspend fun finished(id: java.util.UUID) =
                withTimeout(90000) {
                    WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                        it.state.isFinished
                    }
                }
            val first = finished(work)
            assertEquals(WorkInfo.State.FAILED, first.state)
            assertTrue(first.outputData.getString("error")!!.contains("步骤 4"))
            var row = checkNotNull(repo.dao.allTasks().firstOrNull { it.workId == work.toString() })
            val checkpoint = JSONObject(row.payload).getJSONObject("checkpoint")
            val paths =
                recipe.steps.take(3).map {
                    checkpoint.getJSONObject("recipe-step-${it.id}").getString("path")
                }
            val stamps = paths.map { File(it).lastModified() }
            val hashes = paths.map { PdfAssembly.sha256(File(it)) }
            assertTrue(PdfEngine.text(File(paths[0])).contains("00123"))
            assertTrue(File(paths.last()).name.contains("扫描配方验收_1"))
            recipe =
                recipe.copy(
                    steps =
                        recipe.steps.map {
                            if (it.operation == "save")
                                it.copy(
                                    options = JSONObject().put("destination", "unavailable-after")
                                )
                            else it
                        }
                )
            repo.database.withTransaction {
                val payload = JSONObject(row.payload)
                payload
                    .getJSONObject("input")
                    .put("options", JSONObject().put("recipe", recipe.json()).toString())
                row = row.copy(payload = payload.toString())
                repo.dao.putTask(row)
            }
            val second = finished(DocumentJobs.retry(repo, row))
            assertEquals(WorkInfo.State.FAILED, second.state)
            row = checkNotNull(repo.dao.task(row.id))
            paths.forEachIndexed { index, path ->
                assertEquals(stamps[index], File(path).lastModified())
                assertEquals(hashes[index], PdfAssembly.sha256(File(path)))
            }
            val report = File(row.resourceEntries().first { it.id == "recipe-report" }.path)
            val steps = JSONObject(report.readText()).getJSONArray("steps")
            assertEquals(4, steps.length())
            repeat(3) { assertEquals("reused", steps.getJSONObject(it).getString("status")) }
            assertEquals("failed", steps.getJSONObject(3).getString("status"))
            assertEquals(hash, PdfAssembly.sha256(source))
            File(app.cacheDir, "recipe-proof.json")
                .writeText(
                    JSONObject()
                        .put("task", row.id)
                        .put("recipe", recipe.json())
                        .put("sourceSha256", hash)
                        .put("stages", JSONArray(paths))
                        .put("stageHashes", JSONArray(hashes))
                        .put("stageModified", JSONArray(stamps))
                        .put("report", report.path)
                        .put("completedPrefixReused", true)
                        .toString(2)
                )
        } finally {
            UserSettings.set("workflowRecipes", before)
            UserSettings.prefs.edit().commit()
        }
    }
}
