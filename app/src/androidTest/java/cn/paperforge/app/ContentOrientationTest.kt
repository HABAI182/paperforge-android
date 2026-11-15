package cn.paperforge.app

import android.graphics.BitmapFactory
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.ContentOrientation
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The orientation decision on the device: the same answers as the research code (work/orient/combo.py) on the
 * same finished pages, its cost, and its basic contract.
 *
 * Pages: every PNG/JPG in files/orient-samples (pushed by the parity script), each tested as it is and turned a
 * quarter clockwise. Writes files/orient-out/orient.json with the posterior, the decision, time and memory per page.
 */
@RunWith(AndroidJUnit4::class)
class ContentOrientationTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private fun turned(b: android.graphics.Bitmap, quarters: Int): android.graphics.Bitmap {
        if (quarters == 0) return b
        val m = android.graphics.Matrix().apply { postRotate(90f * quarters) }
        return android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    @Test
    fun decisionsTimeAndMemoryOnPushedPages() {
        val samples = File(app.filesDir, "orient-samples").listFiles().orEmpty()
            .filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg") }.sorted()
        assertTrue("push pages to files/orient-samples", samples.isNotEmpty())
        val out = File(app.filesDir, "orient-out").apply { mkdirs() }
        val rows = JSONArray()
        // Warm-up: model loading is a one-off cost, reported separately.
        val warm = System.nanoTime()
        ContentOrientation.decide(app, BitmapFactory.decodeFile(samples.first().path))
        val loadMs = (System.nanoTime() - warm) / 1e6
        val runtime = Runtime.getRuntime()
        for (file in samples) {
            val page = BitmapFactory.decodeFile(file.path) ?: continue
            for (q in listOf(0, 1)) {
                val input = turned(page, q)
                runtime.gc()
                val javaBefore = runtime.totalMemory() - runtime.freeMemory()
                val nativeBefore = Debug.getNativeHeapAllocatedSize()
                val t0 = System.nanoTime()
                val decision = ContentOrientation.decide(app, input)
                val ms = (System.nanoTime() - t0) / 1e6
                rows.put(
                    JSONObject()
                        .put("file", file.name)
                        .put("extraTurns", q)
                        .put("width", input.width)
                        .put("height", input.height)
                        .put("ms", ms)
                        .put("javaDeltaMb", (runtime.totalMemory() - runtime.freeMemory() - javaBefore) / 1048576.0)
                        .put("nativeDeltaMb", (Debug.getNativeHeapAllocatedSize() - nativeBefore) / 1048576.0)
                        .put("decision", decision?.json() ?: JSONObject.NULL)
                )
                if (input !== page) input.recycle()
            }
            page.recycle()
        }
        File(out, "orient.json").writeText(JSONObject().put("loadMs", loadMs).put("rows", rows).toString(1))
        assertTrue(rows.length() > 0)
    }

    /** A blank page has no evidence: it is never turned. */
    @Test
    fun blankPageIsLeftAlone() {
        val blank = android.graphics.Bitmap.createBitmap(1200, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        blank.eraseColor(android.graphics.Color.WHITE)
        val decision = ContentOrientation.decide(app, blank)
        if (decision != null) assertEquals(0, decision.turnsClockwise)
    }

    /** A page of printed text turned a quarter clockwise is turned back; upright it stays. */
    @Test
    fun printedPageTurnedSidewaysIsTurnedBack() {
        val page = android.graphics.Bitmap.createBitmap(1240, 1754, android.graphics.Bitmap.Config.ARGB_8888)
        page.eraseColor(android.graphics.Color.WHITE)
        val canvas = android.graphics.Canvas(page)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; color = android.graphics.Color.BLACK }
        val title = android.graphics.Paint(paint).apply { textSize = 56f; isFakeBoldText = true }
        canvas.drawText("第三章 函数的极限与连续", 110f, 170f, title)
        val lines = listOf(
            "设函数 f(x) 在点 x0 的某一去心邻域内有定义，如果存在常数 A，",
            "对于任意给定的正数 ε，总存在正数 δ，使得当 0<|x-x0|<δ 时，",
            "对应的函数值 f(x) 都满足不等式 |f(x)-A|<ε，那么常数 A",
            "就叫做函数 f(x) 当 x→x0 时的极限。Theorem 3.1 (uniqueness).",
            "If the limit exists, it is unique. Proof: suppose A and B are limits.",
        )
        var y = 280f
        repeat(6) { for (l in lines) { canvas.drawText(l, 110f, y, paint); y += 52f }; y += 30f }
        val upright = ContentOrientation.decide(app, page)
        assertNotNull(upright)
        assertEquals("upright page", 0, upright!!.turnsClockwise)
        val sideways = turned(page, 1)
        val back = ContentOrientation.decide(app, sideways)
        assertNotNull(back)
        assertEquals("page turned a quarter clockwise", 1, back!!.turnsClockwise)
        assertEquals(270, back.correction)
    }
}
