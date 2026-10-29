package cn.paperforge.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.network.lan.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes the shipped HTML/JS in the Android browser engine, including incremental SHA and PUT.
 */
@RunWith(AndroidJUnit4::class)
class LanBrowserTest {
    @Test
    fun shippedBrowserClientPairsAndSendsBinary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledger = LanLedger.get(context)
        val before = ledger.items.value.map { it.id }.toSet()
        val peersBefore = ledger.peers.value.map { it.id }.toSet()
        val proof = File(context.filesDir, "lan-proof").apply { mkdirs() }
        val bytes = ByteArray(400003) { ((it * 17 + 31) % 256).toByte() }
        val expected = LanLedger.digest(bytes)
        val server = LanHttpServer(context, 0, "127.0.0.1")
        var view: WebView? = null
        ActivityScenario.launch(LanActivity::class.java).use { scenario ->
            fun js(code: String): String {
                val ready = CountDownLatch(1)
                var value = ""
                scenario.onActivity {
                    view!!.evaluateJavascript(code) {
                        value = it
                        ready.countDown()
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                return value
            }
            try {
                scenario.onActivity { activity ->
                    view =
                        WebView(activity).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                        }
                    activity.setContentView(view)
                    view!!.loadUrl(
                        "http://127.0.0.1:${server.port}/#pair=${server.ticket.value.nonce}"
                    )
                }
                val deadline = System.currentTimeMillis() + 15000
                while (
                    System.currentTimeMillis() < deadline &&
                        js("document.getElementById('workspace')?.hidden === false") != "true"
                ) Thread.sleep(100)
                assertEquals("true", js("document.getElementById('workspace').hidden === false"))
                js(
                    "(()=>{const a=new Uint8Array(400003);for(let i=0;i<a.length;i++)a[i]=(i*17+31)%256;const dt=new DataTransfer();dt.items.add(new File([a],'浏览器样本.bin',{type:'application/octet-stream'}));const input=document.getElementById('files');input.files=dt.files;input.dispatchEvent(new Event('change'));document.querySelector('#upload-queue button').click();return true;})()"
                )
                while (
                    System.currentTimeMillis() < deadline &&
                        ledger.items.value.none { it.id !in before && it.state == "READY" }
                ) Thread.sleep(100)
                val row = ledger.items.value.first { it.id !in before && it.name == "浏览器样本.bin" }
                assertEquals("READY", row.state)
                assertEquals(expected, LanLedger.hash(ledger.file(row.id)))
                assertTrue(
                    js("document.getElementById('upload-queue').textContent").contains("手机已接收并核对")
                )
                scenario.onActivity {
                    Bitmap.createBitmap(view!!.width, view!!.height, Bitmap.Config.ARGB_8888)
                        .also { bitmap ->
                            view!!.draw(Canvas(bitmap))
                            proof.resolve("browser.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
                        }
                }
                proof
                    .resolve("browser.json")
                    .writeText(
                        JSONObject()
                            .put("engine", "Android WebView / shipped assets")
                            .put("bytes", bytes.size)
                            .put("sha256", expected)
                            .put("verified", true)
                            .put("body", js("document.body.innerText"))
                            .toString(2)
                    )
            } finally {
                scenario.onActivity { view?.destroy() }
                server.close()
                ledger.items.value.filter { it.id !in before }.forEach { ledger.remove(it.id) }
                ledger.peers.value.filter { it.id !in peersBefore }.forEach { ledger.revoke(it.id) }
            }
        }
    }
}
