package cn.paperforge.app.quality

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.PaperForgeApp
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #27: drive the in-app entry (`MainViewModel.removeObjectWatermarks`) against the owner's device
 * sample, exactly as the PDF toolbox does. The sample stays on the device, never in the repository.
 */
@RunWith(AndroidJUnit4::class)
class WatermarkEntrySampleTest {
    private fun digest(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun inAppEntryCleansSampleIntoCopy() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val sample = File(app.filesDir, "samples/camscanner-sample-3.pdf")
        assertTrue("缺少设备端样本", sample.exists())
        val sourceBytes = sample.length()
        val sourceHash = digest(sample)
        val doc = app.repository.registerPdf(sample, "watermark-entry-sample", listed = false)
        val vm = withContext(Dispatchers.Main) { MainViewModel(app) }
        withContext(Dispatchers.Main) {
            vm.selected.value = doc
            vm.removeObjectWatermarks()
        }
        assertTrue("入口没有进入处理状态", vm.busy.value)
        withTimeout(180_000) { vm.busy.first { !it } }
        val output = vm.output.value ?: error("入口没有生成副本: ${vm.outputNotice.value}")
        assertTrue("输出不是另存副本", output.canonicalFile != sample.canonicalFile)
        assertTrue("成品不存在或为空", output.length() > 0)
        assertTrue("留下 .part 残留", !File(output.path + ".part").exists())
        assertEquals("原件长度被改写", sourceBytes, sample.length())
        assertEquals("原件内容被改写", sourceHash, digest(sample))
        val (text, pages, imageCounts) =
            PDDocument.load(output).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                val counts =
                    (0 until pdf.numberOfPages).map { index ->
                        val page = pdf.getPage(index)
                        page.resources?.xObjectNames?.count { name ->
                            page.resources?.getXObject(name) is PDImageXObject
                        } ?: 0
                    }
                Triple(text, pdf.numberOfPages, counts)
            }
        assertTrue("文字层未清空: ${text.trim().take(30)}", text.isBlank())
        assertEquals("第 1 页图片数不是 1", 1, imageCounts.first())
        val notice = vm.outputNotice.value?.second.orEmpty()
        assertTrue(
            "结果提示缺少水印计数: $notice",
            notice.contains("图片水印") && notice.contains("文字水印"),
        )
        val proof = File(app.filesDir, "watermark-proof").apply { mkdirs() }
        JSONObject()
            .put("synthetic", false)
            .put("ownerSample", "camscanner-sample-3.pdf")
            .put("entry", "MainViewModel.removeObjectWatermarks")
            .put("textLayerChars", text.trim().length)
            .put("pages", pages)
            .put("pageImagesByPage", org.json.JSONArray(imageCounts))
            .put("pageImages", imageCounts.first())
            .put("notice", notice)
            .put("sourceBytes", sourceBytes)
            .put("outputBytes", output.length())
            .put("partLeftover", File(output.path + ".part").exists())
            .also { File(proof, "in-app-entry.json").writeText(it.toString(2)) }
    }

    @Test
    fun noCandidatesDoesNotCreateCopy() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val plain = File(app.cacheDir, "watermark-entry-none.pdf")
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle.A4)
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("plain document body, no corner mark")
                content.endText()
            }
            pdf.save(plain)
        }
        val doc = app.repository.registerPdf(plain, "watermark-entry-none", listed = false)
        val vm = withContext(Dispatchers.Main) { MainViewModel(app) }
        withContext(Dispatchers.Main) {
            vm.output.value = null
            vm.selected.value = doc
            vm.removeObjectWatermarks()
        }
        assertTrue("入口没有进入检查状态", vm.busy.value)
        withTimeout(60_000) { vm.busy.first { !it } }
        assertTrue("未检测到候选时仍生成副本", vm.output.value == null)
        val notices = generateSequence { vm.messages.tryReceive().getOrNull() }.toList()
        assertTrue(
            "缺少明确提示: $notices",
            notices.any { it.contains("未检测到可识别的对象级水印") },
        )
    }
}
