package cn.paperforge.app

import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.imaging.SpreadGutter
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import kotlin.math.abs
import kotlin.math.exp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Open-spread detection on drawn pages: a bound spread's gutter shadow is found where it is, and what only looks
 * like a middle line is not a spread: a two-column page's gap, the edge of a dark picture, a sheet folded once.
 * The real-photo evaluation is in docs/development/SCAN-ENGINE-2026-11-14.md.
 */
class SpreadGutterTest {
    @Before
    fun opencv() {
        InstrumentationRegistry.getInstrumentation()
        assertTrue(OpenCVLoader.initLocal())
    }

    /** Paper with rows of "words" in [columns] blocks; [shade] darkens the paper at column x by its value. */
    private fun page(width: Int, height: Int, columns: List<IntRange>, shade: (Int) -> Double): Mat {
        val m = Mat(height, width, CvType.CV_8UC1)
        val row = ByteArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = (232 - shade(x)).toInt().coerceIn(0, 255).toByte()
            m.put(y, 0, row)
        }
        var y = (height * .08).toInt()
        while (y < height * .92) {
            for (c in columns) {
                var x = c.first
                while (x < c.last) {
                    val word = 20 + (x * 7 + y * 3) % 40
                    Imgproc.rectangle(m, Point(x.toDouble(), y.toDouble()), Point(minOf(c.last, x + word).toDouble(), y + 9.0), Scalar(40.0), -1)
                    x += word + 12
                }
            }
            y += 26
        }
        return m
    }

    @Test
    fun aBoundSpreadsShadowIsTheGutter() {
        val w = 1600
        val gutter = 830
        val spread = page(w, 1100, listOf(110..760, 900..1500)) { x -> 70 * exp(-((x - gutter) / 28.0).let { it * it }) }
        try {
            val found = SpreadGutter.find(spread, SpreadGutter.Strict)
            assertTrue(found.reason, found.found)
            assertEquals(gutter / (w - 1f), found.x!!, .01f)
            assertTrue(found.seamLeft!!.top < found.x!! && found.seamRight!!.top > found.x!!)
        } finally {
            spread.release()
        }
    }

    @Test
    fun aColumnGapAPictureEdgeAndAFoldAreNotSpreads() {
        val w = 1600
        // Two text columns on one sheet: a white gap, no shadow.
        val columns = page(w, 1100, listOf(110..760, 840..1500)) { 0.0 }
        // A dark picture on the right half: darker on one side of its edge only.
        val picture = page(w, 1100, listOf(110..700)) { x -> if (x > 820) 150.0 else 0.0 }
        try {
            assertFalse(SpreadGutter.find(columns, SpreadGutter.Strict).found)
            assertFalse(SpreadGutter.find(columns, SpreadGutter.Asked).found)
            assertFalse(SpreadGutter.find(picture, SpreadGutter.Strict).found)
        } finally {
            columns.release()
            picture.release()
        }
        // A sheet folded once down its middle, photographed upright: the shadow is there, but the crop is taller
        // than wide, so no capture marks it as a spread.
        val folded = page(1100, 1500, listOf(80..500, 600..1020)) { x -> 70 * exp(-((x - 550) / 28.0).let { it * it }) }
        val rgb = Mat()
        try {
            Imgproc.cvtColor(folded, rgb, Imgproc.COLOR_GRAY2RGB)
            assertTrue(SpreadGutter.find(folded, SpreadGutter.Strict).found)
            assertNull(SpreadGutter.mark(rgb, Quad.Full))
        } finally {
            folded.release()
            rgb.release()
        }
    }

    @Test
    fun aCaptureMarksASpreadAndTheMarkGoesAfterASplitOrKeep() {
        val spread = page(1600, 1100, listOf(110..760, 900..1500)) { x -> 70 * exp(-((x - 800) / 28.0).let { it * it }) }
        val rgb = Mat()
        try {
            Imgproc.cvtColor(spread, rgb, Imgproc.COLOR_GRAY2RGB)
            val inset = Quad(listOf(PointN(.02f, .02f), PointN(.98f, .02f), PointN(.98f, .98f), PointN(.02f, .98f)))
            val note = SpreadGutter.mark(rgb, inset)
            assertNotNull(note)
            val (top, bottom) = note!!.split(",").map { it.toFloat() }
            assertTrue(abs(top - .5f) < .03f && abs(bottom - .5f) < .03f)
            val metadata =
                JSONObject()
                    .put("capture", JSONObject().put("notes", JSONObject().put(SpreadGutter.NOTE, note)))
                    .toString()
            assertTrue(SpreadGutter.noted(metadata))
            val cleared = SpreadGutter.withoutNote(metadata)
            assertFalse(SpreadGutter.noted(cleared))
            assertTrue(JSONObject(cleared).has("capture"))
            assertFalse(SpreadGutter.noted(null))
            assertFalse(SpreadGutter.noted("{}"))
        } finally {
            spread.release()
            rgb.release()
        }
    }
}
