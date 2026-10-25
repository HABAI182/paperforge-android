package cn.paperforge.app

import android.graphics.*
import android.net.Uri
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class PaddleModelPackFlowTest {
    @Test
    fun officialPackRecognitionFrozenJobAndCancelledLeaseRemoval() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val old =
                listOf("ocrBackend", "ocrPackId", "ocrLanguage", "ocrMode").associateWith {
                    UserSettings.string(it, "")
                }
            val report = JSONObject().put("device", android.os.Build.SUPPORTED_ABIS.joinToString())
            val samples = JSONArray()
            val tasks = mutableListOf<String>()
            val document = repo.newScan()
            var pack: OcrModelPack? = null
            val synthetic = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(synthetic).apply { drawColor(Color.WHITE) }
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 54f
                    typeface = Typeface.DEFAULT
                }
            val truth =
                listOf("PaperForge 00123", "Invoice 2026-10-25", "Total 00077.50", "纸张扫描与文字识别")
            truth.forEachIndexed { i, text -> canvas.drawText(text, 80f, 130f + i * 150, paint) }
            val whole =
                BitmapFactory.decodeFile(
                    File(app.getExternalFilesDir(null), "paddle-reference.png").path
                )
            val photo = Bitmap.createBitmap(whole, 0, 380, 600, 230)
            whole.recycle()
            val photoTruth = "上海斯格威铂尔曼大酒店\n打浦路15号\n绿洲仕格维花园公寓\n打浦路252935号"
            try {
                pack =
                    app.ocrModels.install(
                        Uri.fromFile(File(app.getExternalFilesDir(null), "paddle-proof.pfocr.zip"))
                    )
                assertEquals(
                    pack.id,
                    OcrModelPacks(app).installed.value.single { it.id == pack.id }.id,
                )
                UserSettings.set("ocrPackId", pack.id)
                UserSettings.set("ocrBackend", "PADDLE")
                UserSettings.set("ocrLanguage", "CHINESE")
                UserSettings.set("ocrMode", "FAST")
                val frozen = OcrRequest.current(app.ocrEngine.id)
                val paddle = frozen.bind(app.ocrEngine)
                val native = BundledOcrEngine()
                report.put("pack", pack.manifest).put("engineId", paddle.id)
                for ((name, image, expected) in
                    listOf(
                        Triple("controlled-mixed", synthetic, truth.joinToString("\n")),
                        Triple("photographed-sign-crop", photo, photoTruth),
                    )) {
                    for ((backend, engine) in listOf("paddle" to paddle, "bundled" to native)) {
                        val before = Debug.getNativeHeapAllocatedSize()
                        val start = android.os.SystemClock.elapsedRealtime()
                        val result =
                            engine.recognizeBitmap(image, true, OcrOptions(OcrMode.FAST, false))
                        val elapsed = android.os.SystemClock.elapsedRealtime() - start
                        assertTrue("$name/$backend returned no text", result.lines.isNotEmpty())
                        if (name == "controlled-mixed")
                            assertTrue(result.text, result.text.contains("00123"))
                        assertTrue(
                            result.lines.all {
                                it.corners.size == 4 &&
                                    it.corners.all { p ->
                                        p.x.isFinite() &&
                                            p.y.isFinite() &&
                                            p.x in -.01f..1.01f &&
                                            p.y in -.01f..1.01f
                                    }
                            }
                        )
                        val decoded = OcrLayout.decode(result.encode())
                        assertEquals(result.requestSignature, decoded.requestSignature)
                        assertEquals(result.engineMetrics, decoded.engineMetrics)
                        samples.put(
                            JSONObject()
                                .put("sample", name)
                                .put("engine", backend)
                                .put("truth", expected)
                                .put("recognized", result.text)
                                .put("wallMs", elapsed)
                                .put("nativeHeapBefore", before)
                                .put("nativeHeapAfter", Debug.getNativeHeapAllocatedSize())
                                .put("layout", JSONObject(result.encode()))
                        )
                    }
                }
                // Prepare under Paddle, switch settings, then run the durable task on two pages.
                repeat(2) { index ->
                    val image = File(repo.folder(document.id), "ocr-proof-$index.png")
                    image.outputStream().use {
                        synthetic.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    repo.dao.putPage(
                        PageRow(
                            UUID.randomUUID().toString(),
                            document.id,
                            index,
                            image.path,
                            corners = Quad.Full.encode(),
                            filter = "ORIGINAL",
                        )
                    )
                }
                val row =
                    DocumentJobs.prepare(
                        repo,
                        "OCR",
                        JobInput.from(workDataOf("document" to document.id, "chinese" to true)),
                    )
                tasks += row.id
                val prepared =
                    JSONObject(row.payload).getJSONObject("input").getJSONObject("ocrRequest")
                assertEquals(frozen.engineId, prepared.getString("engine"))
                UserSettings.set("ocrBackend", "MLKIT")
                UserSettings.set("ocrLanguage", "LATIN")
                UserSettings.set("ocrMode", "DETAILED")
                DocumentJobs.schedule(repo, row)
                val work = UUID.fromString(row.workId)
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
                val layouts = repo.dao.pages(document.id).map { OcrLayout.decode(it.ocrLayout!!) }
                assertTrue(
                    layouts.all {
                        it.engineId == frozen.engineId &&
                            it.requestSignature == frozen.signature &&
                            it.recognitionMode == "FAST"
                    }
                )
                report.put(
                    "frozenJob",
                    JSONObject()
                        .put("state", info.state.name)
                        .put("pages", layouts.size)
                        .put("signature", frozen.signature),
                )
                // Reload one selected runtime, hold its native gate and cancel a waiting
                // recognition.
                UserSettings.set("ocrBackend", "PADDLE")
                paddle.recognizeBitmap(synthetic)
                val held = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val blocker = launch {
                    RuntimeBudget.ocr.use(false) {
                        held.complete(Unit)
                        release.await()
                    }
                }
                held.await()
                val waiting =
                    async(start = CoroutineStart.UNDISPATCHED) { paddle.recognizeBitmap(synthetic) }
                val removal =
                    async(start = CoroutineStart.UNDISPATCHED) { app.ocrEngine.remove(pack) }
                try {
                    delay(50)
                    assertFalse(removal.isCompleted)
                    withTimeout(2000) { waiting.cancelAndJoin() }
                    withTimeout(5000) { removal.await() }
                    assertNull(app.ocrModels.find(pack.id))
                    assertEquals(BundledOcrEngine.ID, app.ocrEngine.id)
                    report
                        .put("cancelledWaitingLeaseReleased", true)
                        .put("modelRemovedAfterLease", true)
                } finally {
                    release.complete(Unit)
                    blocker.join()
                    waiting.cancel()
                    removal.cancel()
                }
                assertTrue(
                    runCatching { frozen.bind(app.ocrEngine) }
                        .exceptionOrNull()
                        ?.message
                        ?.contains("重新导入") == true
                )
                report.put("missingPinnedModelFailsClearly", true)
                // Leave the verified package installed for the settings UI check, without enabling
                // it.
                app.ocrModels.install(
                    Uri.fromFile(File(app.getExternalFilesDir(null), "paddle-proof.pfocr.zip"))
                )
            } finally {
                report.put("samples", samples)
                File(app.cacheDir, "paddle-proof.json").writeText(report.toString(2))
                synthetic.recycle()
                photo.recycle()
                tasks.forEach { task ->
                    repo.dao.task(task)?.let {
                        if (it.state in LocalTaskRow.active) DocumentJobs.cancel(repo, it)
                    }
                    DocumentJobs.forget(repo, task)
                }
                repo.deleteDocument(document)
                old.forEach { (key, value) -> UserSettings.set(key, value) }
                app.ocrEngine.trimUnused()
            }
        }
}
