package cn.paperforge.app

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import cn.paperforge.app.system.PhoneEngines
import cn.paperforge.network.NetworkFiles
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class FullPackageFlowTest {
    @Test
    fun singleInstalledIdentityRunsOfficeNativeFormatsArchiveAndSharedFiles() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            assertTrue(PhoneEngines.integrated(app))
            assertTrue(PdfArchive.available(app))
            assertNotNull(FormatJobs.installed(app))
            for (target in
                listOf(
                    "cn.paperforge.office",
                    "cn.paperforge.formats",
                    "cn.paperforge.network",
                )) assertEquals(app.packageName, PhoneEngines.packageFor(app, target))
            val launcher =
                app.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(app.packageName),
                    0,
                )
            assertEquals(
                listOf("cn.paperforge.app.MainActivity"),
                launcher.map { it.activityInfo.name },
            )
            val proof = File(app.getExternalFilesDir(null), "full-package-proof").apply { mkdirs() }
            val source = File(proof, "office.docx")
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("office-job-input.docx")
                .use { from -> source.outputStream().use { from.copyTo(it) } }
            val sourceHash = PdfAssembly.sha256(source)
            suspend fun convert(input: File, kind: String, options: JSONObject): File {
                val work =
                    DocumentJobs.enqueue(
                        app.repository,
                        "CONVERT",
                        JobInput(
                            JSONObject()
                                .put("kind", kind)
                                .put("name", input.name)
                                .put("inputs", JSONArray(listOf(input.path)))
                                .put("names", JSONArray(listOf(input.name)))
                                .put("options", options.toString())
                        ),
                    )
                val result =
                    withTimeout(120000) {
                        WorkManager.getInstance(app)
                            .getWorkInfoByIdFlow(work)
                            .filterNotNull()
                            .first { it.state.isFinished }
                    }
                assertEquals(
                    result.outputData.getString("error"),
                    WorkInfo.State.SUCCEEDED,
                    result.state,
                )
                return File(requireNotNull(result.outputData.getString("path")))
            }
            val office = convert(source, "OFFICE", JSONObject().put("format", "pdf"))
            val officeText =
                PDDocument.load(office).use {
                    assertEquals(2, it.numberOfPages)
                    PDFTextStripper().getText(it)
                }
            assertTrue(officeText.contains("00123") && officeText.contains("200.50"))
            assertTrue(officeText.replace(Regex("\\s+"), "").contains("纸匠Office排版验收"))
            val svg =
                File(proof, "native.svg").apply {
                    writeText(
                        """<svg xmlns="http://www.w3.org/2000/svg" width="600" height="400"><rect width="600" height="400" fill="white"/><rect x="40" y="40" width="120" height="80" fill="#12765e"/><text x="40" y="190" font-size="26">PaperForge FULL 00123</text></svg>"""
                    )
                }
            val formats = convert(svg, "LAYOUT", JSONObject().put("dpi", 144).put("target", "PDF"))
            PDDocument.load(formats).use { assertEquals(1, it.numberOfPages) }
            val reportFile = File(proof, "archive-${System.nanoTime()}.json")
            val report =
                PdfArchive.verify(app, office, reportFile, "2b", "full-proof-${System.nanoTime()}")
            assertEquals("NORMAL", report.getString("jobEndStatus"))
            val networkFile =
                File(app.filesDir, "updates/full-owned-proof.txt").apply {
                    parentFile!!.mkdirs()
                    writeText("PaperForge full shared file")
                }
            val uri = FileProvider.getUriForFile(app, NetworkFiles.authority(app), networkFile)
            assertEquals(app.packageName + ".network.files", uri.authority)
            assertEquals(
                networkFile.readText(),
                app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() },
            )
            assertEquals(sourceHash, PdfAssembly.sha256(source))
            office.copyTo(File(proof, "office.pdf"), overwrite = true)
            formats.copyTo(File(proof, "native.pdf"), overwrite = true)
            File(proof, "proof.json")
                .writeText(
                    JSONObject()
                        .put(
                            "version",
                            app.packageManager.getPackageInfo(app.packageName, 0).versionName,
                        )
                        .put(
                            "versionCode",
                            app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode,
                        )
                        .put("oneLauncher", true)
                        .put("samePackageOfficeAndFormats", true)
                        .put("officePages", 2)
                        .put("officeChineseAndNumericText", true)
                        .put("nativeSvgPages", 1)
                        .put("archiveJobEndStatus", report.getString("jobEndStatus"))
                        .put("networkProvider", uri.authority)
                        .put("sourceUnchanged", true)
                        .put("sourceSha256", sourceHash)
                        .put("defaultPdfOcr", SettingKeys.defaults["searchable"])
                        .put("defaultEnhancement", SettingKeys.defaults["enhanceStrength"])
                        .toString(2)
                )
        }
}
