package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class OcrImprovementTest {
    @Test
    fun automaticModeHonoursLanguagePinsModelsAndFallsBackOffline() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val packs = OcrModelPacks(app)
        val router = RoutingOcrEngine(app, packs)
        val previousBackend = UserSettings[SettingKeys.OcrBackend]
        val previousLanguage = UserSettings[SettingKeys.OcrLanguage]
        val previousPack = UserSettings[SettingKeys.OcrPackId]
        try {
            UserSettings.set(SettingKeys.OcrBackend, "AUTO")
            UserSettings.set(SettingKeys.OcrLanguage, "CHINESE")
            UserSettings.set(SettingKeys.OcrPackId, "")
            val pinned = router.snapshot()
            assertTrue(
                "installed Chinese pack was ignored",
                pinned.id.startsWith("paddle:paddle-ppocrv5-mobile-"),
            )
            UserSettings.set(SettingKeys.OcrBackend, "MLKIT")
            assertEquals(BundledOcrEngine.ID, router.snapshot().id)
            assertEquals("queued operation changed model", pinned.id, router.snapshot(pinned.id).id)
            UserSettings.set(SettingKeys.OcrBackend, "AUTO")
            val emptyPacks = OcrModelPacks(app).apply { installed.value = emptyList() }
            assertEquals(
                "no matching pack must work offline",
                BundledOcrEngine.ID,
                RoutingOcrEngine(app, emptyPacks).snapshot().id,
            )
        } finally {
            UserSettings.set(SettingKeys.OcrBackend, previousBackend)
            UserSettings.set(SettingKeys.OcrLanguage, previousLanguage)
            UserSettings.set(SettingKeys.OcrPackId, previousPack)
            router.trimUnused()
        }
    }

    @Test
    fun existingPaddleModelOnRealBook() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.filesDir, "november-feedback")
        val packs = OcrModelPacks(app)
        val pack =
            checkNotNull(
                packs.installed.value.firstOrNull { it.id.startsWith("paddle-ppocrv5-mobile-") }
            )
        val router = RoutingOcrEngine(app, packs)
        val engine = router.snapshot("paddle:${pack.id}:${pack.fingerprint}:pf1")
        val raw = ScanProcessor.decode(File(folder, "source-crop.png"), 2000)
        val paper = OcrInputPreparation.paper(raw)
        val rows = JSONArray()
        try {
            for ((name, bitmap) in listOf("raw" to raw, "paper" to paper)) {
                val start = System.nanoTime()
                val result = engine.recognizeBitmap(bitmap, true, OcrOptions(OcrMode.FAST))
                File(folder, "ocr-paddle-$name.json").writeText(result.encode())
                rows.put(
                    JSONObject()
                        .put("view", name)
                        .put("milliseconds", (System.nanoTime() - start) / 1e6)
                        .put("lines", result.lines.size)
                        .put("characters", result.text.length)
                        .put("confidence", result.meanConfidence)
                        .put("metrics", JSONObject(result.engineMetrics))
                )
                assertTrue(result.text.length > 100)
            }
            File(folder, "ocr-paddle-metrics.json")
                .writeText(
                    JSONObject()
                        .put("pack", pack.id)
                        .put("fingerprint", pack.fingerprint)
                        .put("rows", rows)
                        .toString(2)
                )
        } finally {
            raw.recycle()
            paper.recycle()
            router.trimUnused()
        }
    }

    private fun line(
        text: String,
        x: Float,
        y: Float,
        right: Float = .8f,
        confidence: Float = .92f,
    ) =
        OcrLine(
            text,
            listOf(PointN(x, y), PointN(right, y), PointN(right, y + .03f), PointN(x, y + .03f)),
            confidence,
            variant = "supplement",
        )

    @Test
    fun recoveredRowsColumnsAndPunctuationSurviveWithoutDuplicates() {
        val base =
            OcrLayout(
                1000,
                1400,
                listOf(line("input_array", .1f, .1f), line("return 0;", .1f, .4f)),
            )
        val other =
            base.copy(
                lines =
                    listOf(
                        line("input_array", .1f, .101f),
                        line("array", .2f, .1f, .5f),
                        line("while (1)", .1f, .25f),
                        line(";", .82f, .25f, .84f),
                        line("另一栏", .86f, .25f, .98f),
                        line("ghost", .1f, .7f, confidence = .4f),
                    )
            )
        val result = OcrLineFusion.merge(base, other, true)
        assertEquals(
            listOf("input_array", "return 0;", "while (1)", ";", "另一栏"),
            result.lines.map { it.text },
        )
        assertEquals(base.lines.first().corners, result.lines.first().corners)
    }

    @Test
    fun realBookRecognitionViews() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.filesDir, "november-feedback").apply { mkdirs() }
        val raw = ScanProcessor.decode(File(folder, "source-crop.png"), 2000)
        val paper = OcrInputPreparation.paper(raw)
        val engine = BundledOcrEngine()
        val rows = JSONArray()
        val anchors =
            listOf(
                "input_array",
                "print_array",
                "insert",
                "remove",
                "query",
                "main",
                "option",
                "value",
                "MAXN",
                "while",
                "switch",
                "case",
                "break",
                "select",
                "return",
                "Thanks",
                "用户输入",
                "结束操作",
            )
        try {
            for ((name, bitmap, mode) in
                listOf(
                    Triple("raw", raw, OcrMode.FAST),
                    Triple("paper", paper, OcrMode.FAST),
                    Triple("latin", raw, OcrMode.FAST),
                    Triple("balanced", raw, OcrMode.BALANCED),
                )) {
                val start = System.nanoTime()
                val result = engine.recognizeBitmap(bitmap, name != "latin", OcrOptions(mode))
                File(folder, "ocr-$name.json").writeText(result.encode())
                rows.put(
                    JSONObject()
                        .put("view", name)
                        .put("ms", (System.nanoTime() - start) / 1e6)
                        .put("lines", result.lines.size)
                        .put("characters", result.text.length)
                        .put("confidence", result.meanConfidence)
                        .put("attempts", JSONArray(result.attempts))
                        .put(
                            "anchorsFound",
                            JSONArray(anchors.filter { result.text.contains(it, true) }),
                        )
                        .put(
                            "anchorsMissing",
                            JSONArray(anchors.filterNot { result.text.contains(it, true) }),
                        )
                )
                assertTrue("real printed book produced no text", result.text.length > 100)
            }
            File(folder, "ocr-paper.png").outputStream().use {
                paper.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            File(folder, "ocr-metrics.json")
                .writeText(
                    JSONObject()
                        .put("realPhoto", true)
                        .put("phoneMeasured", false)
                        .put("fullTranscriptAvailable", false)
                        .put("anchorsArePartialManualTranscription", true)
                        .put("rows", rows)
                        .toString(2)
                )
        } finally {
            raw.recycle()
            paper.recycle()
        }
    }
}
