package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import cn.paperforge.network.LanTestTrust
import cn.paperforge.network.lan.*
import java.io.*
import java.net.*
import java.util.UUID
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LanTlsProtectionTest {
    private class WireProxy(target: Int, val tamper: Boolean = false) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        private val wire = ByteArrayOutputStream()
        private val sockets = mutableListOf<Socket>()

        private fun record(bytes: ByteArray) {
            synchronized(wire) { wire.write(bytes) }
        }

        fun bytes() = synchronized(wire) { wire.toByteArray() }

        val worker =
            thread(isDaemon = true) {
                try {
                    listener.accept().use { client ->
                        Socket("127.0.0.1", target).use { upstream ->
                            synchronized(sockets) {
                                sockets += client
                                sockets += upstream
                            }
                            val sender =
                                thread(isDaemon = true) {
                                    try {
                                        val input = DataInputStream(client.getInputStream())
                                        var changed = false
                                        while (true) {
                                            val header = ByteArray(5)
                                            input.readFully(header)
                                            val length =
                                                ((header[3].toInt() and 255) shl 8) or
                                                    (header[4].toInt() and 255)
                                            require(length <= 18432)
                                            val body = ByteArray(length)
                                            input.readFully(body)
                                            record(header)
                                            record(body)
                                            if (
                                                tamper &&
                                                    !changed &&
                                                    header[0].toInt() == 23 &&
                                                    body.isNotEmpty()
                                            ) {
                                                body[body.lastIndex] =
                                                    (body.last().toInt() xor 1).toByte()
                                                changed = true
                                            }
                                            upstream.getOutputStream().write(header)
                                            upstream.getOutputStream().write(body)
                                            upstream.getOutputStream().flush()
                                        }
                                    } catch (_: Exception) {
                                        runCatching { upstream.shutdownOutput() }
                                    }
                                }
                            val data = ByteArray(16384)
                            while (true) {
                                val n = upstream.getInputStream().read(data)
                                if (n < 0) break
                                record(data.copyOf(n))
                                client.getOutputStream().write(data, 0, n)
                                client.getOutputStream().flush()
                            }
                            runCatching { client.shutdownOutput() }
                            sender.join(1000)
                        }
                    }
                } catch (_: Exception) {}
            }

        override fun close() {
            listener.close()
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            worker.join(2000)
        }
    }

    private data class Reply(
        val status: Int,
        val body: ByteArray,
        val headers: String,
        val protocol: String,
    )

    private fun request(
        server: LanHttpServer,
        path: String,
        method: String = "GET",
        body: ByteArray = byteArrayOf(),
        cookie: String = "",
        nonce: String = UUID.randomUUID().toString().replace("-", ""),
        time: Long = System.currentTimeMillis(),
        pin: String = server.fingerprint,
        port: Int = server.port,
        origin: String? = null,
        epoch: String = server.epoch,
    ): Reply {
        (LanTestTrust.factory(pin).createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            socket.soTimeout = 5000
            socket.startHandshake()
            val headers =
                "$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: ${body.size}\r\nContent-Type: application/json\r\nCookie: $cookie\r\nX-PaperForge-Request-Id: $nonce\r\nX-PaperForge-Time: $time\r\nX-PaperForge-Epoch: $epoch\r\n${origin?.let { "Origin: $it\r\n" } ?: ""}Connection: close\r\n\r\n"
            socket.outputStream.apply {
                write(headers.toByteArray())
                write(body)
                flush()
            }
            val response = socket.inputStream.readBytes()
            val text = String(response, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            require(split >= 0)
            return Reply(
                text.substringBefore("\r\n").split(' ')[1].toInt(),
                response.copyOfRange(split + 4, response.size),
                text.substring(0, split),
                socket.session.protocol,
            )
        }
    }

    @Test
    fun identityEncryptedWireTamperingReplayAndSessionExpiry() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val ledger = LanLedger.get(app)
        val server = LanHttpServer(app, 0, "127.0.0.1")
        val created = mutableListOf<String>()
        var peerId: String? = null
        val proof = JSONObject()
        try {
            val hello = request(server, "/api/hello")
            assertEquals(200, hello.status)
            assertTrue(hello.protocol in setOf("TLSv1.2", "TLSv1.3"))
            assertEquals(
                server.fingerprint,
                JSONObject(String(hello.body)).getString("certificateSha256"),
            )
            assertTrue(
                runCatching { request(server, "/api/hello", pin = "0".repeat(64)) }.isFailure
            )
            val ticket = server.ticket.value.pin
            fun pairing(fingerprint: String) =
                JSONObject()
                    .put("nonce", ticket)
                    .put("name", "TLS protocol fixture")
                    .put("certificateSha256", fingerprint)
                    .put("identityConfirmed", true)
                    .toString()
                    .toByteArray()
            assertEquals(401, request(server, "/api/pair", "POST", pairing("0".repeat(64))).status)
            val paired = request(server, "/api/pair", "POST", pairing(server.fingerprint))
            assertEquals(200, paired.status)
            assertTrue(paired.headers.contains("Secure; HttpOnly; SameSite=Strict"))
            val cookie =
                paired.headers
                    .lines()
                    .first { it.startsWith("Set-Cookie:") }
                    .substringAfter(": ")
                    .substringBefore(';')
            val peer = ledger.peers.value.last()
            peerId = peer.id
            assertEquals(
                401,
                request(server, "/api/pair", "POST", pairing(server.fingerprint)).status,
            )
            val nonce = UUID.randomUUID().toString().replace("-", "")
            assertEquals(200, request(server, "/api/state", cookie = cookie, nonce = nonce).status)
            assertEquals(409, request(server, "/api/state", cookie = cookie, nonce = nonce).status)
            assertEquals(
                409,
                request(server, "/api/state", cookie = cookie, epoch = "old-server").status,
            )
            assertEquals(
                409,
                request(
                        server,
                        "/api/state",
                        cookie = cookie,
                        time = System.currentTimeMillis() - 600000,
                    )
                    .status,
            )
            assertEquals(
                403,
                request(server, "/api/state", cookie = cookie, origin = "https://wrong.example")
                    .status,
            )
            val bytes = "SECRET document body - PaperForge TLS fixture".repeat(500).toByteArray()
            val filename = "PRIVATE-secret-file-name.txt"
            WireProxy(server.port).use { proxy ->
                val upload =
                    request(
                        server,
                        "/api/uploads",
                        "POST",
                        JSONObject()
                            .put("name", filename)
                            .put("size", bytes.size)
                            .put("sha256", LanLedger.digest(bytes))
                            .toString()
                            .toByteArray(),
                        cookie,
                        port = proxy.port,
                    )
                assertEquals(200, upload.status)
                val id = JSONObject(String(upload.body)).getString("id")
                created += id
                proxy.worker.join(1500)
                val wire = String(proxy.bytes(), Charsets.ISO_8859_1)
                assertTrue(proxy.bytes().size > 100)
                assertFalse(wire.contains(filename))
                assertFalse(wire.contains(cookie.substringAfter('=')))
                assertFalse(wire.contains("Content-Type: application/json"))
                proof.put("wireBytes", proxy.bytes().size)
                WireProxy(server.port).use { payloadProxy ->
                    assertEquals(
                        200,
                        request(
                                server,
                                "/api/uploads/$id?offset=0",
                                "PUT",
                                bytes,
                                cookie,
                                port = payloadProxy.port,
                            )
                            .status,
                    )
                    payloadProxy.worker.join(1500)
                    assertFalse(
                        String(payloadProxy.bytes(), Charsets.ISO_8859_1)
                            .contains("SECRET document body")
                    )
                }
                assertEquals(LanLedger.digest(bytes), LanLedger.hash(ledger.file(id)))
            }
            WireProxy(server.port, true).use { proxy ->
                assertTrue(
                    runCatching {
                            request(server, "/api/state", cookie = cookie, port = proxy.port)
                        }
                        .isFailure
                )
            }
            Socket("127.0.0.1", server.port).use { raw ->
                raw.soTimeout = 2000
                raw.outputStream.write("GET /api/state HTTP/1.1\r\n\r\n".toByteArray())
                val response =
                    runCatching { raw.inputStream.readBytes() }.getOrDefault(byteArrayOf())
                assertFalse(String(response).contains("HTTP/1.1"))
            }
            val token = cookie.substringAfter('=')
            assertNull(ledger.authenticate(token, peer.created + 2592000001L))
            server.revoke(peer.id)
            assertEquals(401, request(server, "/api/state", cookie = cookie).status)
            proof
                .put("protocol", hello.protocol)
                .put("wrongCertificateRejected", true)
                .put("wrongPairingIdentityRejected", true)
                .put("secureCookie", true)
                .put("pairingReplayRejected", true)
                .put("apiReplayRejected", true)
                .put("staleRequestRejected", true)
                .put("wrongOriginRejected", true)
                .put("filenameSessionAndPayloadEncrypted", true)
                .put("tamperedTlsRecordRejected", true)
                .put("plaintextHttpRejected", true)
                .put("expiredSessionRejected", true)
                .put("revokedSessionRejected", true)
            File(app.getExternalFilesDir(null), "issue4")
                .apply { mkdirs() }
                .resolve("tls-security.json")
                .writeText(proof.toString(2))
        } finally {
            server.close()
            created.forEach(ledger::remove)
            peerId?.let(ledger::revoke)
        }
    }
}
