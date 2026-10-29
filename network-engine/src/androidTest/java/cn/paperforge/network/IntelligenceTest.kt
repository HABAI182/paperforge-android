package cn.paperforge.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.network.intelligence.*
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntelligenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val proof = File(context.filesDir, "intelligence-proof").apply { mkdirs() }

    @Test
    fun localQuotesCustomServiceAndFrozenHistory() = runBlocking {
        val source =
            SourceBundle(
                "功能说明",
                "proof-document",
                listOf(
                    SourcePage(2, "PaperForge keeps original OCR. 文档原件始终保留。"),
                    SourcePage(5, "局域网传输支持断点续传。配对设备可以撤销。"),
                ),
            )
        val original = source.json().toString()
        val digest = source.sha256
        val local = IntelligenceEngine.local(source, "问答", "断点续传")
        assertEquals(listOf(5), local.pages)
        assertTrue(local.answer.contains("断点续传"))
        val received = AtomicReference<JSONObject>()
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val worker =
            Thread {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().buffered()
                        val headers = java.io.ByteArrayOutputStream()
                        var state = 0
                        while (state < 4) {
                            val b = reader.read()
                            if (b < 0) error("missing headers")
                            headers.write(b)
                            state =
                                when {
                                    state == 0 && b == 13 -> 1
                                    state == 1 && b == 10 -> 2
                                    state == 2 && b == 13 -> 3
                                    state == 3 && b == 10 -> 4
                                    else -> 0
                                }
                        }
                        val length =
                            Regex("(?im)^Content-Length: (\\d+)")
                                .find(headers.toString("ISO-8859-1"))!!
                                .groupValues[1]
                                .toInt()
                        val bytes = ByteArray(length)
                        var count = 0
                        while (count < length) {
                            val n = reader.read(bytes, count, length - count)
                            if (n < 0) error("missing body")
                            count += n
                        }
                        received.set(JSONObject(String(bytes, Charsets.UTF_8)))
                        val body =
                            "{\"choices\":[{\"message\":{\"content\":\"依据原文保留OCR [p2]，不属于来源的引用 [p99]\"}}]}"
                                .toByteArray(Charsets.UTF_8)
                        socket
                            .getOutputStream()
                            .write(
                                "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n"
                                    .toByteArray() + body
                            )
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
        val prefs = IntelligencePrefs(context)
        val oldKey = prefs.key()
        var remote: IntelligenceResult? = null
        try {
            prefs.key("paperforge-proof-placeholder")
            assertEquals("paperforge-proof-placeholder", prefs.key())
            assertFalse(
                prefs.store
                    .getString("intelligenceKey", "")!!
                    .contains("paperforge-proof-placeholder")
            )
            remote =
                IntelligenceEngine.remote(
                    source,
                    "摘要",
                    "",
                    "http://127.0.0.1:${server.localPort}/v1",
                    "fixture-protocol",
                    prefs.key(),
                    "简洁回答",
                )
            worker.join(5000)
            assertTrue(
                received
                    .get()
                    .getJSONArray("messages")
                    .getJSONObject(1)
                    .getString("content")
                    .contains("[p5]")
            )
            assertEquals(listOf(2), remote.pages)
            assertEquals(listOf(99), remote.unknownPages)
            IntelligenceFiles.save(context, remote)
            val restored = IntelligenceFiles.read(context, remote.id)
            assertEquals(digest, restored.source.sha256)
            assertEquals(original, source.json().toString())
            assertTrue(restored.markdown().contains("paperforge://document/proof-document?page=5"))
            proof
                .resolve("flow.json")
                .writeText(
                    JSONObject()
                        .put("sourceUnchanged", true)
                        .put("sourceSha256", digest)
                        .put("localResult", local.json())
                        .put("remoteResult", restored.json())
                        .put("service", "owned local protocol fixture; no paid provider contacted")
                        .put("encryptedKeyRoundTrip", true)
                        .toString(2)
                )
        } finally {
            server.close()
            remote?.let { IntelligenceFiles.remove(context, it.id) }
            prefs.key(oldKey)
        }
    }

    @Test
    fun prepareActualTranslationModel() = runBlocking {
        withTimeout(180000) { LocalTranslation.download("en", "zh", false) }
        val installed = LocalTranslation.downloaded()
        assertTrue("zh" in installed)
        proof
            .resolve("models.json")
            .writeText(
                JSONObject()
                    .put("downloaded", org.json.JSONArray(installed.toList()))
                    .put("provider", "ML Kit 17.0.3 / on-demand model")
                    .toString(2)
            )
    }

    @Test
    fun translateWithNetworkAlreadyDisabled() = runBlocking {
        val source =
            SourceBundle(
                "离线翻译样本",
                "proof-document",
                listOf(SourcePage(3, "PaperForge keeps your documents on your phone.")),
            )
        val before = source.sha256
        val value = withTimeout(30000) { LocalTranslation.translate(source, "en", "zh") }
        assertEquals(before, source.sha256)
        assertEquals(listOf(3), value.pages)
        assertTrue(value.answer.any { it.code in 0x4e00..0x9fff })
        proof
            .resolve("translation.json")
            .writeText(
                value
                    .json()
                    .put("sourceUnchanged", true)
                    .put("networkDisabledByHostBeforeTest", true)
                    .toString(2)
            )
    }
}
