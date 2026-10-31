package cn.paperforge.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.OcrLayout
import cn.paperforge.app.model.SmartNaming
import cn.paperforge.app.ocr.BundledOcrEngine
import cn.paperforge.app.ocr.OcrMode
import cn.paperforge.app.ocr.OcrOptions
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #25: run the owner's device sample through the bundled ML Kit engine, feed the cached first-page
 * text into SmartNaming, and prove the synthetic receipt/contract/workbook/note texts hit.
 *
 * The owner sample stays on the device (`files/samples`). Results are written to
 * `files/smart-naming/sample-smart-naming.json` and pulled back to `work/smart-naming/`.
 */
@RunWith(AndroidJUnit4::class)
class SmartNamingSampleTest {
    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap =
        renderer.openPage(index).use { view ->
            val scale = 2000f / maxOf(view.width, view.height)
            val bitmap =
                Bitmap.createBitmap(
                    (view.width * scale).roundToInt().coerceAtLeast(1),
                    (view.height * scale).roundToInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
            bitmap.eraseColor(Color.WHITE)
            view.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            bitmap
        }

    private fun suggestionJson(suggestion: SmartNaming.Suggestion) =
        JSONObject()
            .put("category", suggestion.category.name)
            .put("categoryLabel", suggestion.category.label)
            .put("confidence", suggestion.confidence.toDouble())
            .put("names", JSONArray(suggestion.names))
            .put("evidence", JSONArray(suggestion.evidence))

    @Test
    fun sampleOcrAndSyntheticRulesProduceExplainableNamingJson() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val sample = File(app.filesDir, "samples/camscanner-sample-3.pdf")
            assertTrue("缺少设备端样本", sample.exists())
            val engine = app.ocrEngine.snapshot(BundledOcrEngine.ID)

            var pdfPages = 0
            var layout: OcrLayout? = null
            ParcelFileDescriptor.open(sample, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor
                ->
                PdfRenderer(descriptor).use { renderer ->
                    pdfPages = renderer.pageCount
                    val bitmap = renderPage(renderer, 0)
                    try {
                        layout =
                            engine.recognizeBitmap(bitmap, true, OcrOptions(OcrMode.BALANCED, true))
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            val firstLayout = checkNotNull(layout) { "样本首页没有 OCR 结果" }
            assertTrue("样本没有 OCR 出任何行", firstLayout.lines.isNotEmpty())

            val sampleSuggestion =
                SmartNaming.suggest(
                    SmartNaming.Input(
                        ocrText = firstLayout.text,
                        pageCount = pdfPages,
                        currentName = sample.nameWithoutExtension,
                    )
                )
            // The real sample is a formula/handwriting page: no receipt/contract/workbook/note
            // evidence,
            // so the honest answer is "no candidate", not an invented name.
            assertEquals(SmartNaming.Category.OTHER, sampleSuggestion.category)
            assertTrue("真实样本不应硬凑候选名称", sampleSuggestion.names.isEmpty())
            assertEquals(0f, sampleSuggestion.confidence, 0f)
            assertTrue(sampleSuggestion.evidence.isEmpty())

            // Read back through the same cached-layout path the rename dialog uses.
            var dbText = ""
            var dbSuggestion: SmartNaming.Suggestion? = null
            val doc = repo.newScan()
            try {
                val file = repo.captureFile(doc.id)
                val bitmap =
                    ParcelFileDescriptor.open(sample, ParcelFileDescriptor.MODE_READ_ONLY).use {
                        descriptor ->
                        PdfRenderer(descriptor).use { renderer -> renderPage(renderer, 0) }
                    }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val page =
                    PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path, filter = "ORIGINAL")
                repo.dao.putPage(page)
                repo.cacheOcr(page, firstLayout)
                val stored = repo.dao.pages(doc.id)
                dbText =
                    stored.firstOrNull()?.ocrLayout?.let { OcrLayout.decode(it).text }
                        ?: stored.firstOrNull()?.ocrText.orEmpty()
                dbSuggestion =
                    SmartNaming.suggest(
                        SmartNaming.Input(
                            ocrText = dbText,
                            pageCount = stored.size,
                            currentName = doc.title,
                        )
                    )
            } finally {
                repo.deleteDocument(doc)
            }
            assertEquals("设备缓存后的 OCR 文本与识别结果不一致", firstLayout.text, dbText)
            assertEquals("从数据库回读后建议变化", sampleSuggestion, dbSuggestion)

            // Synthetic, constructed inside the test: not real photos, only proof the rules fire.
            val cases =
                listOf(
                    Triple(
                        "receipt",
                        """
                    星河超市
                    发票
                    2026-09-29
                    苹果 10.00
                    合计 ￥128.00
                    """
                            .trimIndent(),
                        SmartNaming.Category.RECEIPT,
                    ),
                    Triple(
                        "contract",
                        """
                    采购合同
                    甲方：星河科技有限公司
                    乙方：张三
                    签订日期：2026年9月29日
                    第一条 服务条款
                    """
                            .trimIndent(),
                        SmartNaming.Category.CONTRACT,
                    ),
                    Triple(
                        "workbook",
                        """
                    数学练习册
                    姓名：李小明
                    班级：三年二班
                    学号：2026001
                    第12页 口算练习
                    """
                            .trimIndent(),
                        SmartNaming.Category.WORKBOOK,
                    ),
                    Triple(
                        "note",
                        """
                    周末计划
                    买菜
                    读书
                    跑步
                    写周报
                    整理照片
                    给妈妈打电话
                    """
                            .trimIndent(),
                        SmartNaming.Category.NOTE,
                    ),
                )
            val synthetic = JSONArray()
            cases.forEach { (label, text, expected) ->
                val suggestion =
                    SmartNaming.suggest(
                        SmartNaming.Input(ocrText = text, pageCount = 1, currentName = "未命名文档")
                    )
                assertEquals("$label 分类未命中", expected, suggestion.category)
                assertTrue("$label 没有候选名称", suggestion.names.isNotEmpty())
                assertTrue("$label 候选名称超过 3 个", suggestion.names.size <= 3)
                assertTrue(
                    "$label 候选名称没有分类前缀",
                    suggestion.names.all { it.startsWith(expected.label) },
                )
                assertTrue("$label 缺少 evidence", suggestion.evidence.isNotEmpty())
                synthetic.put(
                    JSONObject()
                        .put("synthetic", true)
                        .put("label", label)
                        .put("text", text.replace("\n", " / "))
                        .put("suggestion", suggestionJson(suggestion))
                )
            }

            val result =
                JSONObject()
                    .put("issue", 25)
                    .put("synthetic", false)
                    .put("ownerSample", "camscanner-sample-3.pdf")
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("engine", firstLayout.engineId)
                    .put("engineVersion", firstLayout.engineVersion)
                    .put("language", "CHINESE")
                    .put("mode", "BALANCED")
                    .put("threshold", 1.0)
                    .put("pdfPages", pdfPages)
                    .put("ocrPages", 1)
                    .put("ocrLines", firstLayout.lines.size)
                    .put(
                        "firstLines",
                        JSONArray(firstLayout.lines.take(12).map { it.text.take(80) }),
                    )
                    .put("firstPageText", firstLayout.text.take(240))
                    .put("realSampleSuggestion", suggestionJson(sampleSuggestion))
                    .put(
                        "dbRoundTrip",
                        JSONObject()
                            .put("textMatches", firstLayout.text == dbText)
                            .put("suggestionMatches", sampleSuggestion == dbSuggestion),
                    )
                    .put("syntheticCases", synthetic)
            val proof = File(app.filesDir, "smart-naming").apply { mkdirs() }
            File(proof, "sample-smart-naming.json").writeText(result.toString(2))
        }
}
