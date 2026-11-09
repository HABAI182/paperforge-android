package cn.paperforge.network

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import cn.paperforge.network.lan.*
import com.tom_roush.pdfbox.pdmodel.*
import java.io.File
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LanLibraryIntegrationTest {
    @Test
    fun pairedBrowserListsAppFoldersCoversAndDownloadsPdfAndScan() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val token = UUID.randomUUID().toString()
        val pdf = File(repo.exports, "library-$token.pdf")
        PDDocument().use {
            it.addPage(PDPage())
            it.addPage(PDPage())
            it.save(pdf)
        }
        val doc =
            repo.importPdf(android.net.Uri.fromFile(pdf)).copy(folder = "课程/数学", title = "局域网 PDF")
        repo.dao.putDocument(doc)
        val scan = repo.newScan().copy(title = "扫描草稿", folder = "课堂笔记")
        repo.dao.putDocument(scan)
        val photo = repo.captureFile(scan.id)
        Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            photo.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
            recycle()
        }
        repo.dao.putPage(
            PageRow(UUID.randomUUID().toString(), scan.id, 0, photo.path, filter = "ORIGINAL")
        )
        val trash =
            DocumentRow(
                UUID.randomUUID().toString(),
                "回收站文档",
                pdfPath = pdf.path,
                kind = "pdf",
                trashedAt = System.currentTimeMillis(),
            )
        repo.dao.putDocument(trash)
        val server = LanHttpServer(app, 0, "127.0.0.1")
        var cookie = ""
        fun request(
            path: String,
            body: JSONObject? = null,
            range: String? = null,
        ): Pair<Int, ByteArray> {
            val conn =
                URL("https://127.0.0.1:${server.port}$path").openConnection() as HttpsURLConnection
            conn.sslSocketFactory = LanTestTrust.factory(server.fingerprint)
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.setRequestProperty(
                "X-PaperForge-Request-Id",
                UUID.randomUUID().toString().replace("-", ""),
            )
            conn.setRequestProperty("X-PaperForge-Time", System.currentTimeMillis().toString())
            conn.setRequestProperty("X-PaperForge-Epoch", server.epoch)
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            if (range != null) conn.setRequestProperty("Range", range)
            conn.connectTimeout = 5000
            conn.readTimeout = 30000
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            conn.getHeaderField("Set-Cookie")?.let { cookie = it.substringBefore(';') }
            val bytes =
                (if (code < 400) conn.inputStream else conn.errorStream).use { it.readBytes() }
            conn.disconnect()
            return code to bytes
        }
        try {
            assertEquals(401, request("/api/library").first)
            assertEquals(401, request("/library/${doc.id}/pdf").first)
            assertEquals(
                200,
                request(
                        "/api/pair",
                        JSONObject()
                            .put("nonce", server.ticket.value.pin)
                            .put("name", "文档库验收-$token")
                            .put("certificateSha256", server.fingerprint)
                            .put("identityConfirmed", true),
                    )
                    .first,
            )
            val result = request("/api/library")
            assertEquals(200, result.first)
            val json = JSONObject(String(result.second))
            assertTrue(json.getBoolean("available"))
            val array = json.getJSONArray("documents")
            val rows = (0 until array.length()).map { array.getJSONObject(it) }
            assertEquals("课程/数学", rows.first { it.getString("id") == doc.id }.getString("folder"))
            assertTrue(rows.any { it.getString("id") == scan.id })
            assertFalse(rows.any { it.getString("id") == trash.id })
            val cover = request("/library/${doc.id}/cover")
            assertEquals(200, cover.first)
            assertTrue(cover.second.size > 100)
            val info = request("/api/library/${doc.id}")
            assertEquals(200, info.first)
            val details = JSONObject(String(info.second))
            assertEquals(2, details.getInt("pages"))
            assertEquals(pdf.length(), details.getLong("bytes"))
            assertEquals("课程/数学", details.getString("folder"))
            for (page in 0..1) {
                val preview = request("/library/${doc.id}/preview/$page")
                assertEquals(200, preview.first)
                val image =
                    android.graphics.BitmapFactory.decodeByteArray(
                        preview.second,
                        0,
                        preview.second.size,
                    )
                assertTrue(image.width > 1000)
                image.recycle()
            }
            val download = request("/library/${doc.id}/pdf")
            assertEquals(200, download.first)
            assertArrayEquals(pdf.readBytes(), download.second)
            val partial = request("/library/${doc.id}/pdf", range = "bytes=0-15")
            assertEquals(206, partial.first)
            assertArrayEquals(pdf.readBytes().copyOfRange(0, 16), partial.second)
            val scanResult = request("/library/${scan.id}/pdf")
            assertEquals(200, scanResult.first)
            PDDocument.load(scanResult.second).use { assertEquals(1, it.numberOfPages) }
            androidx.test.core.app.ActivityScenario.launch(LanActivity::class.java).use { scenario
                ->
                var web: android.webkit.WebView? = null
                fun js(code: String): String {
                    val ready = java.util.concurrent.CountDownLatch(1)
                    var value = ""
                    scenario.onActivity {
                        web!!.evaluateJavascript(code) {
                            value = it
                            ready.countDown()
                        }
                    }
                    assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return value
                }
                try {
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .waitForIdleSync()
                    Thread.sleep(400)
                    val nativeShot =
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                            .uiAutomation
                            .takeScreenshot()
                    val proof = File(app.filesDir, "flow-review-ui").apply { mkdirs() }
                    nativeShot?.let { image ->
                        File(proof, "lan-native.png").outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        image.recycle()
                    }
                    scenario.onActivity { activity ->
                        web =
                            android.webkit.WebView(activity).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = LanTestTrust.browser(server.fingerprint)
                            }
                        activity.setContentView(web)
                        web!!.loadUrl(
                            "https://127.0.0.1:${server.port}/#pair=${server.ticket.value.nonce}&cert=${server.fingerprint}"
                        )
                    }
                    val deadline = System.currentTimeMillis() + 20000
                    while (
                        System.currentTimeMillis() < deadline &&
                            js(
                                "document.getElementById('certificate-fingerprint')?.textContent.length > 0"
                            ) != "true"
                    ) Thread.sleep(100)
                    js(
                        "document.getElementById('device-name').value='文档库验收-$token';document.getElementById('identity-confirmed').checked=true;document.getElementById('pair-form').requestSubmit()"
                    )
                    while (
                        System.currentTimeMillis() < deadline &&
                            js("document.querySelectorAll('.document-card').length >= 2") != "true"
                    ) Thread.sleep(100)
                    assertEquals(
                        "true",
                        js("document.querySelectorAll('.document-card').length >= 2"),
                    )
                    assertEquals("\"文件就在手边\"", js("document.querySelector('h1').textContent"))
                    js(
                        "document.getElementById('library-search').value='扫描草稿';document.getElementById('library-search').dispatchEvent(new Event('input'))"
                    )
                    assertEquals("1", js("document.querySelectorAll('.document-card').length"))
                    js("document.getElementById('library-all').click()")
                    assertEquals(
                        "false",
                        js("document.getElementById('library-download').disabled"),
                    )
                    js(
                        "document.getElementById('library-search').value='';document.getElementById('library-search').dispatchEvent(new Event('input'))"
                    )
                    assertEquals(
                        "true",
                        js("document.documentElement.scrollWidth <= window.innerWidth + 1"),
                    )
                    js("openDocumentPreview('library','${doc.id}')")
                    val previewDeadline = System.currentTimeMillis() + 15000
                    while (
                        System.currentTimeMillis() < previewDeadline &&
                            js("document.getElementById('preview-image').naturalWidth > 1000") !=
                                "true"
                    ) Thread.sleep(100)
                    assertEquals("true", js("document.getElementById('document-preview').open"))
                    assertEquals(
                        "true",
                        js(
                            "document.getElementById('preview-details').textContent.includes('课程/数学')"
                        ),
                    )
                    js("document.getElementById('preview-next').click()")
                    assertEquals(
                        "\"2 / 2\"",
                        js("document.getElementById('preview-page').textContent"),
                    )
                    assertEquals(
                        "true",
                        js("document.documentElement.scrollWidth <= window.innerWidth + 1"),
                    )
                    scenario.onActivity {
                        val bitmap =
                            Bitmap.createBitmap(web!!.width, web!!.height, Bitmap.Config.ARGB_8888)
                        web!!.draw(android.graphics.Canvas(bitmap))
                        File(app.filesDir, "flow-review-ui/lan-browser.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                } finally {
                    scenario.onActivity { web?.destroy() }
                }
            }
            assertTrue(photo.isFile)
            assertTrue(pdf.isFile)
        } finally {
            LanLedger.get(app)
                .peers
                .value
                .filter { it.name == "文档库验收-$token" }
                .forEach { server.revoke(it.id) }
            server.close()
            repo.deleteDocument(doc)
            repo.deleteDocument(scan)
            repo.dao.deleteDocumentRaw(trash.id)
            pdf.delete()
        }
    }
}
