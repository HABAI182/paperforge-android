package cn.paperforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.network.GitHubUpdates
import cn.paperforge.network.InstalledPackage
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One focused protocol check for the three visible update outcomes and unsupported devices. */
@RunWith(AndroidJUnit4::class)
class UpdateCatalogFeedbackTest {
    private class Catalog : Closeable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        @Volatile var unavailable = false

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client =
                        try {
                            socket.accept()
                        } catch (_: Exception) {
                            break
                        }
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) {}
                        val body = if (path.contains("/releases?")) releases() else manifest()
                        val bytes = body.toByteArray()
                        it.getOutputStream().apply {
                            write(
                                "HTTP/1.1 ${if (unavailable) "503 Unavailable" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        private fun releases() =
            JSONArray()
                .put(
                    JSONObject()
                        .put("draft", false)
                        .put("prerelease", false)
                        .put("tag_name", "v1.0.1")
                        .put("html_url", "https://github.com/fixture/paperforge/releases/v1.0.1")
                        .put(
                            "assets",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("id", 1)
                                        .put("name", "release-manifest.json")
                                        .put("size", 500)
                                )
                                .put(
                                    JSONObject()
                                        .put("id", 2)
                                        .put("name", "PaperForge-full.apk")
                                        .put("size", 42)
                                ),
                        )
                )
                .toString()

        private fun manifest() =
            JSONObject()
                .put("distribution", "full")
                .put("channel", "stable")
                .put(
                    "artifacts",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("applicationId", "cn.paperforge.app")
                                .put("versionCode", 10004)
                                .put("versionName", "1.0.1")
                                .put("abis", JSONArray().put("x86_64"))
                                .put("file", "PaperForge-full.apk")
                                .put("bytes", 42)
                                .put("sha256", "1".repeat(64))
                        ),
                )
                .toString()

        override fun close() {
            socket.close()
        }
    }

    @Test
    fun latestAvailableUnavailableAndIncompatibleAreDistinct() = runBlocking {
        Catalog().use { server ->
            val client = GitHubUpdates("", server.url, integrated = true)
            val latest =
                client.check(
                    "fixture/paperforge",
                    "stable",
                    mapOf("cn.paperforge.app" to InstalledPackage(10004, "1.0.1")),
                    listOf("x86_64"),
                )
            assertEquals(1, latest.releases)
            assertTrue(latest.candidates.isEmpty() && latest.warnings.isEmpty())
            val older = mapOf("cn.paperforge.app" to InstalledPackage(10003, "1.0.0"))
            val available = client.check("fixture/paperforge", "stable", older, listOf("x86_64"))
            assertEquals(10004L, available.candidates.single().versionCode)
            val incompatible =
                client.check("fixture/paperforge", "stable", older, listOf("arm64-v8a"))
            assertTrue(incompatible.candidates.isEmpty() && incompatible.warnings.isNotEmpty())
            server.unavailable = true
            assertTrue(
                runCatching {
                        client.check("fixture/paperforge", "stable", older, listOf("x86_64"))
                    }
                    .isFailure
            )
        }
    }
}
