package cn.paperforge.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.LocalTaskRow
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner tests by adding photos from the gallery. This goes through that entry point and waits for
 * the background work, so what it saves is what the phone shows. Photos come from files/phone-samples;
 * an instrument argument `tag` names the output folder.
 */
@RunWith(AndroidJUnit4::class)
class ImportPathTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    @Test
    fun galleryPhotosAreFlattenedLikeCaptures() = runBlocking {
        val tag = InstrumentationRegistry.getArguments().getString("tag") ?: "import"
        val root = File(app.filesDir, "phone-out/$tag").apply { mkdirs() }
        val samples = File(app.filesDir, "phone-samples").listFiles().orEmpty().sorted()
        assertTrue("no photos in files/phone-samples", samples.isNotEmpty())
        UserSettings.set(SettingKeys.Filter, "WORKBOOK")
        val repo = app.repository
        val document = repo.newScan()
        val started = System.nanoTime()
        repo.importImages(document.id, samples.map(Uri::fromFile))
        val limit = System.nanoTime() + samples.size * 120_000_000_000L
        while (repo.dao.allTasks().any { it.documentId == document.id && it.state in LocalTaskRow.active }) {
            assertTrue("import did not finish", System.nanoTime() < limit)
            delay(500)
        }
        val elapsed = (System.nanoTime() - started) / 1e9
        val pages = repo.dao.pages(document.id)
        assertEquals(samples.size, pages.size)
        val rows = JSONArray()
        for ((index, page) in pages.withIndex()) {
            val name = samples[index].nameWithoutExtension
            val bitmap = ScanProcessor.render(page, 2400)
            File(root, "$name-3-final.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            rows.put(
                JSONObject()
                    .put("file", samples[index].name)
                    .put("method", page.detectionMethod ?: "")
                    .put("width", bitmap.width)
                    .put("height", bitmap.height)
                    .put("metadata", page.processingMetadata ?: "")
            )
            bitmap.recycle()
            assertEquals("page $name was not flattened", "flat-document", page.detectionMethod)
        }
        File(root, "runs.json").writeText(JSONObject().put("seconds", elapsed).put("pages", rows).toString(1))
    }

    /**
     * The camera's mode rail writes the shared filter. On the owner's phone a page came out unflattened,
     * with raw paper and curved rules, and this is one way to get there: the rail left on another mode.
     * Also the path the OCR entry takes: import, wait, then read the finished page.
     */
    @Test
    fun aCameraModeLeftOnDoesNotChangeGalleryImports() = runBlocking {
        val sample = File(app.filesDir, "phone-samples").listFiles().orEmpty().minOrNull()
        assertTrue("no photos in files/phone-samples", sample != null)
        val repo = app.repository
        try {
            for ((mode, filter, aspect) in listOf(Triple("clean", "CLEAN", 0f), Triple("card", "ORIGINAL", 85.6f / 54f), Triple("color", "AUTO", 0f))) {
                UserSettings.set("captureMode", mode)
                UserSettings.set(SettingKeys.Filter, filter)
                UserSettings.set(SettingKeys.ScanAspect, aspect)
                val document = repo.newScan()
                repo.importImages(document.id, listOf(Uri.fromFile(sample)))
                repo.awaitCaptures(document.id)
                val page = repo.dao.pages(document.id).single()
                assertEquals("rail on $mode: not flattened", "flat-document", page.detectionMethod)
                assertEquals("rail on $mode: blended with the raw photo", 1f, page.enhanceStrength)
            }
        } finally {
            UserSettings.set("captureMode", "document")
            UserSettings.set(SettingKeys.Filter, "WORKBOOK")
            UserSettings.set(SettingKeys.ScanAspect, 0f)
        }
    }
}
