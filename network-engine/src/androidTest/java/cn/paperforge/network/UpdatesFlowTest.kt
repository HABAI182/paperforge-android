package cn.paperforge.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import java.io.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdatesFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val proof = File(context.filesDir, "updates-proof").apply { mkdirs() }
    private val input = File(context.filesDir, "updates-proof-input")

    private class Server(private val apk: ByteArray? = null) : Closeable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val address = "http://127.0.0.1:${socket.localPort}"
        val ranges = CopyOnWriteArrayList<Long>()
        @Volatile var failCatalog = false
        var route: (String) -> String? = { null }

        init {
            Thread {
                    while (!socket.isClosed) {
                        val client =
                            try {
                                socket.accept()
                            } catch (_: IOException) {
                                break
                            }
                        Thread {
                                try {
                                    client.use { s ->
                                        val reader = s.getInputStream().bufferedReader()
                                        val first = reader.readLine() ?: return@use
                                        val path = first.split(' ')[1]
                                        val headers = mutableMapOf<String, String>()
                                        while (true) {
                                            val line = reader.readLine() ?: break
                                            if (line.isEmpty()) break
                                            headers[line.substringBefore(':').lowercase()] =
                                                line.substringAfter(':').trim()
                                        }
                                        val asset = path == "/asset" && apk != null
                                        val start =
                                            if (asset)
                                                headers["range"]
                                                    ?.removePrefix("bytes=")
                                                    ?.removeSuffix("-")
                                                    ?.toLong() ?: 0
                                            else 0
                                        val body =
                                            if (asset) apk!!
                                            else if (failCatalog && path.contains("/releases?"))
                                                null
                                            else route(path)?.toByteArray()
                                        val code =
                                            if (body == null) 404 else if (start > 0) 206 else 200
                                        val bytes = body ?: "{}".toByteArray()
                                        val output = s.getOutputStream()
                                        output.write(
                                            ("HTTP/1.1 $code ${if(code==200) "OK" else if(code==206) "Partial Content" else "Not Found"}\r\nConnection: close\r\nContent-Length: ${bytes.size - start}\r\n" +
                                                    (if (start > 0)
                                                        "Content-Range: bytes $start-${bytes.size-1}/${bytes.size}\r\n"
                                                    else "") +
                                                    "\r\n")
                                                .toByteArray()
                                        )
                                        if (asset) ranges += start
                                        var position = start.toInt()
                                        while (position < bytes.size) {
                                            val count = minOf(8192, bytes.size - position)
                                            output.write(bytes, position, count)
                                            output.flush()
                                            position += count
                                            if (asset) Thread.sleep(3)
                                        }
                                    }
                                } catch (_: Exception) {
                                    /* A paused client closes its current transfer. */
                                }
                            }
                            .apply {
                                isDaemon = true
                                start()
                            }
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
        }

        override fun close() {
            socket.close()
        }
    }

    @Test
    fun channelAbiSelectionAndHttpFailureAreDistinct() = runBlocking {
        Server().use { server ->
            fun manifest(code: Int, channel: String) =
                JSONObject()
                    .put("channel", channel)
                    .put(
                        "artifacts",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("applicationId", "cn.paperforge.app")
                                    .put("versionCode", code)
                                    .put("versionName", if (code == 8) "0.7.0-beta.2" else "0.6.0")
                                    .put("file", "PaperForge.apk")
                                    .put("bytes", 42)
                                    .put("sha256", "1".repeat(64))
                                    .put("abis", JSONArray(listOf("arm64-v8a")))
                            ),
                    )
            fun release(id: Int, preview: Boolean) =
                JSONObject()
                    .put("draft", false)
                    .put("prerelease", preview)
                    .put("tag_name", if (preview) "v0.7.0-beta.2" else "v0.6.0")
                    .put("html_url", "https://github.com/fixture/paperforge/releases/$id")
                    .put(
                        "assets",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", id)
                                    .put("name", "release-manifest.json")
                                    .put("size", 200)
                            )
                            .put(
                                JSONObject()
                                    .put("id", 20 + id)
                                    .put("name", "PaperForge.apk")
                                    .put("size", 42)
                            ),
                    )
            val releases = JSONArray().put(release(2, true)).put(release(1, false))
            server.route = { path ->
                when {
                    path.contains("/releases?") -> releases.toString()
                    path.endsWith("/assets/1") -> manifest(6, "stable").toString()
                    path.endsWith("/assets/2") -> manifest(8, "prerelease").toString()
                    else -> null
                }
            }
            val client = GitHubUpdates("", server.address)
            val installed = mapOf("cn.paperforge.app" to InstalledPackage(7, "0.6.1-dev"))
            val stable =
                client.check("fixture/paperforge", "stable", installed, listOf("arm64-v8a"))
            val preview =
                client.check("fixture/paperforge", "prerelease", installed, listOf("arm64-v8a"))
            assertTrue(stable.candidates.isEmpty())
            assertEquals(1, stable.releases)
            assertEquals(8L, preview.candidates.single().versionCode)
            assertTrue(
                client
                    .check("fixture/paperforge", "prerelease", installed, listOf("x86_64"))
                    .candidates
                    .isEmpty()
            )
            assertTrue(GitHubUpdates.compareVersions("1.0.0-beta.11", "1.0.0-beta.2") > 0)
            assertTrue(GitHubUpdates.compareVersions("1.0.0", "1.0.0-rc.1") > 0)
            server.failCatalog = true
            val failed =
                runCatching { client.check("fixture/paperforge", "prerelease", installed) }
                    .exceptionOrNull()
            assertNotNull(failed)
            assertTrue(failed!!.message!!.contains("私有仓库"))
            File(proof, "catalog.json")
                .writeText(
                    JSONObject()
                        .put("stableReleases", stable.releases)
                        .put("previewVersionCode", preview.candidates.single().versionCode)
                        .put("wrongAbiExcluded", true)
                        .put("httpFailureIsNotNoUpdate", true)
                        .put("failure", failed.message)
                        .toString(2)
                )
        }
    }

    @Test
    fun cancelledDownloadResumesAndVerifiesFullSignature() = runBlocking {
        val fixture = JSONObject(File(input, "fixture.json").readText())
        val source = File(input, "fixture.apk")
        val spec = fixture.getJSONObject("fixture.apk")
        val prefs = NetworkPreferences(context)
        val beforeEnabled = prefs.enabled
        val beforeToken = prefs.token()
        try {
            prefs.enabled = true
            prefs.token("paperforge-fixture-token")
            assertEquals("paperforge-fixture-token", prefs.token())
            Server(source.readBytes()).use { server ->
                val candidate =
                    UpdateCandidate(
                        "cn.paperforge.fixture.update",
                        "下载验收",
                        "2.0.0",
                        2,
                        "fixture.apk",
                        spec.getLong("bytes"),
                        spec.getString("sha256"),
                        server.address + "/asset",
                        "https://github.com/fixture/paperforge",
                        "prerelease",
                        emptyList(),
                    )
                val manager = WorkManager.getInstance(context)
                val first = UpdateFiles.enqueue(context, candidate, restart = true)
                withTimeout(30000) {
                    while (UpdateFiles.part(context, candidate).length() < 262144) delay(20)
                }
                UpdateFiles.pause(context, candidate)
                withTimeout(30000) {
                    manager.getWorkInfoByIdFlow(first).filterNotNull().first {
                        it.state == WorkInfo.State.CANCELLED
                    }
                }
                val retained = UpdateFiles.part(context, candidate).length()
                assertTrue(retained > 0)
                val resumed = UpdateFiles.enqueue(context, candidate)
                val finished =
                    withTimeout(60000) {
                        manager.getWorkInfoByIdFlow(resumed).filterNotNull().first {
                            it.state.isFinished
                        }
                    }
                assertEquals(
                    finished.outputData.toString(),
                    WorkInfo.State.SUCCEEDED,
                    finished.state,
                )
                assertTrue(server.ranges.any { it > 0 })
                val ready = UpdateFiles.ready(context, candidate)
                assertEquals(spec.getString("sha256"), UpdateApkVerifier.sha(ready))
                val verified = JSONObject(File(ready.path + ".json").readText())
                assertTrue(verified.getBoolean("signatureVerified"))
                val modified = File(input, "altered.apk")
                val bad = fixture.getJSONObject("altered.apk")
                val tampered =
                    runCatching {
                            UpdateApkVerifier.verify(
                                context,
                                modified,
                                candidate.copy(
                                    bytes = bad.getLong("bytes"),
                                    sha256 = bad.getString("sha256"),
                                ),
                            )
                        }
                        .exceptionOrNull()
                assertNotNull(tampered)
                assertTrue(tampered!!.message!!.contains("签名校验"))
                File(proof, "download.json")
                    .writeText(
                        JSONObject()
                            .put("bytesBeforePause", retained)
                            .put("requestsStartingAt", JSONArray(server.ranges.toList()))
                            .put("verified", verified)
                            .put("alteredApkRejected", true)
                            .put("tokenEncryptedRoundtrip", true)
                            .toString(2)
                    )
            }
        } finally {
            prefs.enabled = beforeEnabled
            prefs.token(beforeToken)
        }
    }
}
