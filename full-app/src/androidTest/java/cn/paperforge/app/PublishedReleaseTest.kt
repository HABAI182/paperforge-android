package cn.paperforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.network.GitHubUpdates
import cn.paperforge.network.InstalledPackage
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * After a release is published: the app's own update check offers exactly the published APK to a phone on the
 * previous version, through the GitHub API and through the releases/latest manifest the app falls back to, and
 * offers nothing once that version is installed.
 *
 * Runs only when given the release to expect:
 * `-e repo HABAI182/paperforge-android -e versionCode 20042 -e versionName 2.0.1 -e previousCode 20036
 *  -e sha256 <hex> -e bytes <n>`
 * plus, for a device without internet access, `-e served <dir>`: a folder on the device holding the published
 * data fetched elsewhere, byte for byte — `releases.json` (the API's release list), `assets/<id>` (each listed
 * release's release-manifest.json by asset id) and `latest-manifest.json` (releases/latest/download/release-manifest.json).
 * Without `served` it reads github.com directly.
 */
@RunWith(AndroidJUnit4::class)
class PublishedReleaseTest {
    private val args = InstrumentationRegistry.getArguments()

    /** A local stand-in for api.github.com and github.com that answers with the published files only. */
    private class Served(dir: File, repo: String) : AutoCloseable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val root = "http://127.0.0.1:${socket.localPort}"
        @Volatile var apiDown = false

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use { c ->
                        val line = c.getInputStream().bufferedReader().readLine() ?: return@use
                        val path = line.split(' ').getOrElse(1) { "/" }
                        val body: ByteArray? =
                            when {
                                path.startsWith("/repos/$repo/releases?") ->
                                    if (apiDown) null
                                    else if (path.endsWith("page=1")) File(dir, "releases.json").readBytes()
                                    else "[]".toByteArray()
                                path.startsWith("/repos/$repo/releases/assets/") ->
                                    File(dir, "assets/" + path.substringAfterLast('/')).takeIf { it.isFile }?.readBytes()
                                path == "/$repo/releases/latest/download/release-manifest.json" ->
                                    File(dir, "latest-manifest.json").readBytes()
                                else -> null
                            }
                        val head =
                            if (body == null) "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            else "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        c.getOutputStream().apply {
                            write(head.toByteArray())
                            body?.let { write(it) }
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    @Test
    fun thePreviousVersionIsOfferedThePublishedApk(): Unit = runBlocking {
        val repo = args.getString("repo")
        assumeTrue("no published release given", repo != null)
        val code = args.getString("versionCode")!!.toLong()
        val name = args.getString("versionName")!!
        val previous = args.getString("previousCode")!!.toLong()
        val sha = args.getString("sha256")!!.lowercase()
        val bytes = args.getString("bytes")!!.toLong()
        val served = args.getString("served")?.let { Served(File(it), repo!!) }
        // A 64-bit ARM phone (the only build published), checked as the full app does: integrated, stable, no token.
        val arm = listOf("arm64-v8a")
        val older = mapOf("cn.paperforge.app" to InstalledPackage(previous, "previous"))
        val current = mapOf("cn.paperforge.app" to InstalledPackage(code, name))
        try {
            val updates =
                if (served == null) GitHubUpdates("", integrated = true)
                else GitHubUpdates("", apiRoot = served.root, integrated = true, webRoot = served.root, mirrors = emptyList())
            val routes = mutableListOf("api")
            if (served != null) routes += "latest-manifest fallback"
            for (route in routes) {
                served?.apiDown = route != "api"
                val offered = updates.check(repo!!, "stable", older, arm)
                val app = offered.candidates.single { it.packageName == "cn.paperforge.app" }
                assertEquals(route, code, app.versionCode)
                assertEquals(route, name, app.versionName)
                assertEquals(route, "PaperForge-$name-full-arm64-v8a.apk", app.fileName)
                assertEquals(route, sha, app.sha256)
                assertEquals(route, bytes, app.bytes)
                assertTrue("$route: nothing offered once $name is installed", updates.check(repo, "stable", current, arm).candidates.isEmpty())
                android.util.Log.i("PublishedReleaseTest", "$route offered ${app.versionName} (${app.versionCode}) ${app.sha256} ${app.bytes}")
            }
        } finally {
            served?.close()
        }
    }
}
