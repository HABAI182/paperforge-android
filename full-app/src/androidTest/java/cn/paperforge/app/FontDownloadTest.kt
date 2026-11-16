package cn.paperforge.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.pdf.FontStore
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Font download and import end to end, in the full package (the one with network access), against the real sources.
 * Needs the device online. Results and screenshots go to files/corpus-out/fonts/.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FontDownloadTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity.applicationContext
    private val out by lazy { File(context.filesDir, "corpus-out/fonts").apply { mkdirs() } }
    private val report = JSONObject()

    private fun family(id: String) = FontStore.catalog(context).families.first { it.id == id }

    private fun removeFamily(id: String) =
        FontStore.installed(context).filter { it.family == id }.forEach { FontStore.delete(context, it.sha256) }

    private fun noPartials() = FontStore.folder(context).listFiles().orEmpty().none { it.name.endsWith(".part") }

    private fun save(name: String) = File(out, "$name.json").writeText(report.toString(1))

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A dead host and a source that answers with the wrong file both fail over to the real CDN; bytes are verified. */
    @Test
    fun a_downloadFallsBackAndVerifies() = runBlocking {
        assertTrue("full package must hold INTERNET", FontStore.canDownload(context))
        val caladea = family("caladea")
        removeFamily("caladea")
        val real = FontStore.catalog(context).sources
        val sources = listOf(
            "https://unreachable.paperforge.invalid/{path}",
            // Serves a different file (the family's licence) for every font path: must fail the checksum.
            "https://cdn.jsdelivr.net/gh/google/fonts@{commit}/ofl/caladea/OFL.txt?{path}",
        ) + real
        val hosts = LinkedHashSet<String>()
        val t0 = System.nanoTime()
        val installed = FontStore.download(context, caladea, sources) { _, _, host -> hosts += host }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(caladea.files.size, installed.size)
        assertTrue("caladea installed", "caladea" in FontStore.installedFamilies(context))
        assertFalse("the wrong-bytes source must not be installed from", installed.any { it.file.length() == 0L })
        for (font in installed) {
            val meta = JSONObject(File(font.file.parentFile, font.sha256 + ".json").readText())
            assertEquals("download", meta.getString("origin"))
            assertEquals("OFL-1.1", meta.getString("license"))
            assertTrue(meta.getString("source") in real.map { java.net.URL(it.replace("{commit}", "x").replace("{path}", "y")).host })
        }
        assertTrue("no partial files left", noPartials())
        report.put("fallback", JSONObject().put("ms", ms).put("files", installed.size).put("hostsTried", hosts.toList().toString())
            .put("bytes", installed.sumOf { it.file.length() }))
        save("a-fallback")
    }

    /** Import: dedupes by content, refuses CFF OpenType and collections with a reason; a downloaded font embeds in a PDF. */
    @Test
    fun b_importRefusesAndEmbeds() = runBlocking {
        val downloaded = FontStore.installed(context).firstOrNull { it.family == "caladea" }
            ?: FontStore.download(context, family("caladea")) { _, _, _ -> }.first()
        // Import of the same bytes from "the phone" lands on the same stored font.
        val copy = File(context.cacheDir, "MyFont.ttf").apply { downloaded.file.copyTo(this, overwrite = true) }
        val imported = FontStore.import(context, Uri.fromFile(copy))
        assertEquals(downloaded.sha256, imported.sha256)
        assertTrue(imported.name.isNotBlank())
        // Re-importing a downloaded font keeps it a complete, licensed member of its family.
        assertEquals("download", imported.origin)
        assertEquals("caladea", imported.family)
        assertTrue("caladea" in FontStore.installedFamilies(context))
        // CFF-outline OpenType and font collections are refused with a reason, and leave nothing behind.
        for ((name, head) in listOf("cff.otf" to "OTTO", "fonts.ttc" to "ttcf")) {
            val fake = File(context.cacheDir, name).apply { writeBytes(head.toByteArray() + ByteArray(256)) }
            try {
                FontStore.import(context, Uri.fromFile(fake))
                fail("$name should be refused")
            } catch (e: FontStore.Refused) {
                report.put("refused-$name", e.message)
            }
        }
        val garbage = File(context.cacheDir, "broken.ttf").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        try {
            FontStore.import(context, Uri.fromFile(garbage)); fail("garbage should be refused")
        } catch (e: FontStore.Refused) {
            report.put("refused-garbage", e.message)
        }
        assertTrue(noPartials())
        // The downloaded font embeds: write a PDF with it, read it back, check the embedded font program.
        val pdf = File(out, "embedded.pdf")
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            val font = PDType0Font.load(doc, downloaded.file)
            PDPageContentStream(doc, page).use { s ->
                s.beginText(); s.setFont(font, 18f); s.newLineAtOffset(72f, 700f); s.showText("PaperForge Caladea 2026"); s.endText()
            }
            doc.save(pdf)
        }
        PDDocument.load(pdf).use { doc ->
            val font = doc.getPage(0).resources.fontNames.map { doc.getPage(0).resources.getFont(it) }.single()
            assertTrue("font program embedded", font.isEmbedded)
            report.put("embedded", JSONObject().put("font", font.name).put("pdfBytes", pdf.length()))
        }
        save("b-import")
    }

    /** Cancelling mid-download leaves no partial file and no half-installed family. */
    @Test
    fun c_cancelLeavesNothing() = runBlocking {
        val big = family("noto-serif-tc")
        removeFamily(big.id)
        var seen = 0L
        val job = async(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { FontStore.download(context, big) { done, _, _ -> seen = done } }
        }
        val start = System.currentTimeMillis()
        while (seen < 256 * 1024 && System.currentTimeMillis() - start < 60_000) delay(100)
        job.cancel()
        runCatching { job.await() }
        delay(500)
        assertTrue("download had started", seen > 0)
        assertTrue("no partial files after cancel", noPartials())
        assertFalse(big.id in FontStore.installedFamilies(context))
        report.put("cancel", JSONObject().put("bytesBeforeCancel", seen))
        save("c-cancel")
    }

    /** The user's path: Settings > 字体管理 > 下载字体, download a family, see it installed and listed. */
    @Test
    fun d_downloadThroughTheUi() {
        removeFamily("cousine")
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle {
            vm.settingsSection.value = "fonts"
            vm.screen.value = "settings"
        }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("settings-font-manager", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-font-manager", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("font-tab-download", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("font-tab-download", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("font-download-cousine", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        shot("d1-download-tab")
        val t0 = System.nanoTime()
        compose.onNodeWithTag("font-download-cousine", useUnmergedTree = true).performScrollTo().performClick()
        Thread.sleep(300)
        shot("d2-downloading")
        compose.waitUntil(120_000) {
            compose.onAllNodes(hasTestTag("font-manager-message").and(hasText("已安装", substring = true)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        shot("d3-installed")
        assertTrue("cousine" in FontStore.installedFamilies(context))
        compose.onNodeWithTag("font-tab-installed", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Cousine", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        shot("d4-installed-tab")
        report.put("ui", JSONObject().put("ms", ms).put("family", "cousine"))
        save("d-ui")
    }
}
