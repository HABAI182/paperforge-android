package cn.paperforge.network

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.network.lan.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanTransferTest {
    @Test
    fun actualPairResumeRangeAndSourceOwnership() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledger = LanLedger.get(context)
        val proof = File(context.filesDir, "lan-proof").apply { mkdirs() }
        val sourceId = UUID.randomUUID().toString()
        val original =
            ledger.file(sourceId).apply {
                parentFile!!.mkdirs()
                context.resources.openRawResource(R.drawable.brand_logo_source).use { i ->
                    outputStream().use { i.copyTo(it) }
                }
            }
        val hash = LanLedger.hash(original)
        val bytes = original.readBytes()
        var server = LanHttpServer(context, 0, "127.0.0.1")
        var cookie = ""
        val requests = mutableListOf<JSONObject>()
        data class Reply(val code: Int, val bytes: ByteArray, val cookie: String) {
            fun json() = JSONObject(String(this.bytes, Charsets.UTF_8))
        }
        fun request(
            path: String,
            method: String = "GET",
            body: ByteArray? = null,
            range: String? = null,
        ): Reply {
            val conn =
                URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.requestMethod = method
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
            if (range != null) conn.setRequestProperty("Range", range)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val response =
                (if (code < 400) conn.inputStream else conn.errorStream).use { it.readBytes() }
            val session = conn.getHeaderField("Set-Cookie")?.substringBefore(';').orEmpty()
            requests +=
                JSONObject()
                    .put("path", path)
                    .put("status", code)
                    .put("responseBytes", response.size)
                    .put("range", range)
            conn.disconnect()
            return Reply(code, response, session)
        }
        val created = mutableListOf<String>()
        try {
            assertEquals(401, request("/api/state").code)
            assertTrue(String(request("/").bytes).contains("PaperForge"))
            val paired =
                request(
                    "/api/pair",
                    "POST",
                    JSONObject()
                        .put("nonce", server.ticket.value.pin)
                        .put("name", "局域网集中样本")
                        .toString()
                        .toByteArray(),
                )
            assertEquals(200, paired.code)
            cookie = paired.cookie
            assertTrue(cookie.startsWith("pf_session="))
            val peer = ledger.peers.value.last()
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    original,
                    "来源 图片.png",
                )
            val outgoing = ledger.add(uri, peer.id)
            created += outgoing.id
            assertEquals("来源 图片.png", outgoing.name)
            assertEquals(hash, outgoing.sha256)
            val meta =
                JSONObject()
                    .put("name", "接收样本.png")
                    .put("size", bytes.size)
                    .put("sha256", hash)
                    .put("mime", "image/png")
                    .toString()
                    .toByteArray()
            var row = request("/api/uploads", "POST", meta).json()
            val id = row.getString("id")
            created += id
            val checkpoint = 311296
            assertEquals(
                200,
                request("/api/uploads/$id?offset=0", "PUT", bytes.copyOfRange(0, checkpoint)).code,
            )
            assertEquals(checkpoint.toLong(), ledger.file(id).length())
            server.pause(id)
            assertEquals(
                409,
                request(
                        "/api/uploads/$id?offset=$checkpoint",
                        "PUT",
                        bytes.copyOfRange(checkpoint, checkpoint + 1024),
                    )
                    .code,
            )
            ledger.progress(id, checkpoint.toLong(), "PAUSED")
            server.close()
            assertTrue(ledger.item(outgoing.id) != null)
            val disk = JSONObject(File(ledger.root, "ledger.json").readText())
            assertTrue(disk.toString().contains(id))
            server = LanHttpServer(context, 0, "127.0.0.1")
            row = request("/api/uploads", "POST", meta).json()
            assertEquals(id, row.getString("id"))
            assertEquals(checkpoint.toLong(), row.getLong("offset"))
            var offset = checkpoint
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + 524288)
                assertEquals(
                    200,
                    request(
                            "/api/uploads/$id?offset=$offset",
                            "PUT",
                            bytes.copyOfRange(offset, end),
                        )
                        .code,
                )
                offset = end
            }
            assertEquals("READY", ledger.item(id)!!.state)
            assertEquals(hash, LanLedger.hash(ledger.file(id)))
            val a = request("/download/${outgoing.id}", range = "bytes=0-${checkpoint-1}")
            val b = request("/download/${outgoing.id}", range = "bytes=$checkpoint-")
            assertEquals(206, a.code)
            assertEquals(206, b.code)
            assertArrayEquals(bytes, a.bytes + b.bytes)
            assertEquals(hash, LanLedger.hash(original))
            server.revoke(peer.id)
            assertEquals(401, request("/api/state").code)
            proof
                .resolve("flow.json")
                .writeText(
                    JSONObject()
                        .put("sourceBytes", bytes.size)
                        .put("sourceSha256", hash)
                        .put("checkpoint", checkpoint)
                        .put("serverRestart", true)
                        .put("readyState", ledger.item(id)!!.state)
                        .put("sourceUnchanged", true)
                        .put("rangeReassemblyEqual", true)
                        .put("requests", org.json.JSONArray(requests))
                        .toString(2)
                )
        } finally {
            proof.resolve("requests.json").writeText(org.json.JSONArray(requests).toString(2))
            server.close()
            created.forEach(ledger::remove)
            original.delete()
            ledger.peers.value.filter { it.name == "局域网集中样本" }.forEach { ledger.revoke(it.id) }
        }
    }
}
