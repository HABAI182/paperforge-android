package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ToneZones
import java.io.File
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/** Rules for the hysteresis tone stage. Pages are synthetic; metrics go to files/tone-zones. */
@RunWith(AndroidJUnit4::class)
class ToneZonesRulesTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private fun config(overrides: JSONObject, block: () -> Unit) {
        val snapshot = ScanConfig.replaceForBenchmark(overrides)
        try {
            block()
        } finally {
            ScanConfig.restoreAfterBenchmark(snapshot)
        }
    }

    private fun page(width: Int, height: Int, value: Int): Mat {
        val page = Mat(height, width, CvType.CV_8UC1)
        page.setTo(Scalar(value.toDouble()))
        return page
    }

    private fun field(width: Int, height: Int, value: Float): Mat {
        val field = Mat(height, width, CvType.CV_32FC1)
        field.setTo(Scalar(value.toDouble()))
        return field
    }

    private fun fill(page: Mat, x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        for (y in y0 until y1) for (x in x0 until x1) page.put(y, x, value.toDouble())
    }

    private fun mean(page: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            sum += page.get(y, x)[0]
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun bytes(page: Mat): ByteArray =
        ByteArray(page.cols() * page.rows()).also { page.get(0, 0, it) }

    private fun metrics(name: String, values: JSONObject) {
        val dir = File(context.filesDir, "tone-zones").apply { mkdirs() }
        File(dir, "$name.json").writeText(values.toString(2))
    }

    private fun mapped(width: Int, height: Int, draw: (Mat) -> Unit, read: (Mat) -> Unit) {
        val page = page(width, height, 250)
        draw(page)
        val field = field(width, height, 250f)
        try {
            ToneZones.apply(page, field, 1f)
            read(page)
        } finally {
            field.release()
            page.release()
        }
    }

    @Test
    fun inkLandsOnTargetAndPaperOnPaperWhite() =
        mapped(400, 400, { fill(it, 40, 40, 160, 120, 45) }) { page ->
            // Off ruled paper, print is drawn a dark grey (toneZonesTargetInkPrint), as the reference scanner draws it.
            assertEquals(35.0, mean(page, 60, 60, 140, 100), 3.0)
            assertEquals(255.0, mean(page, 60, 240, 340, 360), 1.0)
        }

    @Test
    fun showThroughWithNoDarkCoreDisappears() =
        mapped(
            400,
            300,
            {
                // A faint "reverse side" word and a large grey wash, neither with any dark core.
                for (i in 0 until 8) fill(it, 60 + i * 12, 60, 66 + i * 12, 70, 212)
                fill(it, 200, 120, 340, 260, 225)
                // Paper diffuses what shows through it, so a ghost has soft edges.
                org.opencv.imgproc.Imgproc.GaussianBlur(it, it, org.opencv.core.Size(0.0, 0.0), 2.5)
            },
        ) { page ->
            val word = mean(page, 60, 60, 150, 70)
            val wash = mean(page, 220, 140, 320, 240)
            metrics("ghost", JSONObject().put("word", word).put("wash", wash))
            assertTrue("show-through survived: $word", word >= 252.0)
            assertTrue("shading survived: $wash", wash >= 252.0)
        }

    /** Reported on a real page: faint pencil was erased along with show-through. */
    @Test
    fun faintCrispPencilAndPunctuationStay() =
        mapped(
            500,
            300,
            {
                fill(it, 60, 60, 63, 90, 195) // a faint pencil stroke: light, but with sharp edges
                fill(it, 70, 60, 73, 90, 195)
                fill(it, 80, 75, 110, 78, 195)
                fill(it, 200, 60, 202, 62, 100) // the two dots of a colon
                fill(it, 200, 68, 202, 70, 100)
            },
        ) { page ->
            val pencil = mean(page, 60, 62, 63, 88)
            val colon = mean(page, 200, 60, 202, 62)
            metrics("faint", JSONObject().put("pencil", pencil).put("colon", colon))
            assertTrue("faint pencil was erased: $pencil", pencil < 225.0)
            assertTrue("a colon was erased: $colon", colon < 200.0)
        }

    @Test
    fun faintLongRuleSurvivesWithItsOwnTone() =
        mapped(
            400,
            300,
            {
                fill(it, 40, 60, 360, 61, 215) // 1 px faint rule
                fill(it, 40, 150, 360, 152, 200) // 2 px rule
            },
        ) { page ->
            val faint = mean(page, 60, 60, 340, 61)
            val rule = mean(page, 60, 150, 340, 152)
            metrics("rules", JSONObject().put("faint", faint).put("rule", rule))
            assertTrue("faint rule was erased: $faint", faint < 235.0)
            assertTrue("rule was erased: $rule", rule < 225.0)
        }

    @Test
    fun faintTailConnectedToAStrokeSurvivesButAGhostCrossingItIsCutShort() =
        mapped(
            400,
            300,
            {
                // Ruled notebook paper, where show-through is common. On an unruled page a faint straight line
                // is kept on purpose: it is a drawing's construction line.
                for (k in 0 until 10) fill(it, 0, 20 + 30 * k, 400, 22 + 30 * k, 150)
                fill(it, 100, 100, 112, 200, 40) // stroke
                // A tail touching it. The narrow curve draws anything lighter than about .55 of paper here
                // as paper: that is what cleans around strokes, and its documented cost is a paler tail.
                fill(it, 112, 150, 116, 153, 120)
                fill(it, 112, 180, 200, 183, 212) // a ghost line running out of the stroke
            },
        ) { page ->
            val tail = mean(page, 112, 150, 116, 153)
            val farGhost = mean(page, 150, 180, 200, 183)
            metrics("tail", JSONObject().put("tail", tail).put("farGhost", farGhost))
            assertTrue("attached tail was erased: $tail", tail < 240.0)
            assertTrue("ghost kept far from the stroke: $farGhost", farGhost >= 252.0)
        }

    @Test
    fun darkWedgeAtTheFrameGoesButHandwritingAtTheFrameStays() =
        mapped(
            500,
            500,
            {
                fill(it, 0, 240, 22, 290, 20) // thick dark wedge against the left frame
                fill(it, 0, 100, 40, 102, 40) // thin stroke touching the same frame
                fill(it, 40, 100, 42, 120, 40)
                fill(it, 240, 240, 262, 290, 20) // the same block away from the frame is ink
            },
        ) { page ->
            val wedge = mean(page, 2, 250, 18, 280)
            val stroke = mean(page, 2, 100, 38, 102)
            val inner = mean(page, 245, 250, 258, 280)
            metrics("edge-debris", JSONObject().put("wedge", wedge).put("stroke", stroke).put("inner", inner))
            assertTrue("wedge at the frame survived: $wedge", wedge >= 250.0)
            assertTrue("handwriting at the frame was erased: $stroke", stroke < 120.0)
            assertTrue("ink away from the frame was erased: $inner", inner < 120.0)
        }

    @Test
    fun skinColouredMarkInTheEdgeBandGoesButBlueInkStays() {
        val width = 500
        val height = 500
        val page = Mat(height, width, CvType.CV_8UC3)
        page.setTo(Scalar(248.0, 248.0, 248.0))
        fun paint(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, g: Int, b: Int) {
            val colour = byteArrayOf(r.toByte(), g.toByte(), b.toByte())
            for (y in y0 until y1) for (x in x0 until x1) page.put(y, x, colour)
        }
        paint(12, 200, 18, 260, 150, 100, 60) // the shadow line between two fingers, not at the frame
        paint(14, 300, 16, 360, 30, 40, 150) // a pen stroke of blue ink in the same band
        val field = field(width, height, 248f)
        try {
            ToneZones.apply(page, field, 1f)
            fun luma(x: Int, y: Int) = page.get(y, x).let { .299 * it[0] + .587 * it[1] + .114 * it[2] }
            val finger = luma(15, 230)
            val ink = luma(15, 330)
            metrics("edge-colour", JSONObject().put("finger", finger).put("ink", ink))
            assertTrue("skin-coloured mark survived: $finger", finger >= 245.0)
            assertTrue("blue ink was erased: $ink", ink < 150.0)
        } finally {
            field.release()
            page.release()
        }
    }

    @Test
    fun aLineAlongTheFrameAndASliverGoButARuleAndATallLetterStay() =
        mapped(
            1000,
            1000,
            {
                fill(it, 8, 100, 11, 700, 60) // the edge of a stack, along the left frame
                fill(it, 0, 500, 900, 503, 60) // a ruled line meeting that frame
                fill(it, 400, 0, 420, 8, 40) // the tip of a letter on the sheet behind
                fill(it, 600, 0, 606, 40, 40) // a real letter cut by the frame
                fill(it, 60, 100, 63, 700, 60) // a line well inside the page
            },
        ) { page ->
            val edgeLine = mean(page, 8, 150, 11, 450)
            val rule = mean(page, 100, 501, 800, 502)
            val sliver = mean(page, 402, 1, 418, 7)
            val letter = mean(page, 601, 5, 605, 35)
            val inner = mean(page, 60, 150, 63, 450)
            metrics(
                "frame-lines",
                JSONObject().put("edgeLine", edgeLine).put("rule", rule).put("sliver", sliver).put("letter", letter).put("inner", inner),
            )
            assertTrue("line along the frame survived: $edgeLine", edgeLine >= 250.0)
            assertTrue("ruled line was erased: $rule", rule < 150.0)
            assertTrue("sliver of the sheet behind survived: $sliver", sliver >= 250.0)
            assertTrue("letter cut by the frame was erased: $letter", letter < 150.0)
            assertTrue("line inside the page was erased: $inner", inner < 150.0)
        }

    /** Reported on a real page: a thumb touching a ruled line stayed, as the whole mark looked neutral. */
    /** A page with an object or a shadow at its left frame, its own lighting already in the paper field. */
    private fun withWarmLeft(draw: (Mat, (Int, Int, Int, Int, Int, Int, Int) -> Unit) -> Unit, read: (Mat) -> Unit) {
        val width = 800
        val height = 600
        val page = Mat(height, width, CvType.CV_8UC3)
        page.setTo(Scalar(240.0, 235.0, 225.0))
        fun paint(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, g: Int, b: Int) {
            val colour = byteArrayOf(r.toByte(), g.toByte(), b.toByte())
            for (y in y0 until y1) for (x in x0 until x1) page.put(y, x, colour)
        }
        paint(0, 150, 170, 450, 150, 105, 60)
        draw(page, ::paint)
        val field = field(width, height, 235f)
        org.opencv.imgproc.Imgproc.rectangle(field, org.opencv.core.Rect(0, 150, 170, 300), Scalar(112.0), -1)
        try {
            ToneZones.apply(page, field, 1f)
            read(page)
        } finally {
            field.release()
            page.release()
        }
    }

    private fun lumaAt(page: Mat, x: Int, y: Int) = page.get(y, x).let { .299 * it[0] + .587 * it[1] + .114 * it[2] }

    @Test
    fun writingInAWarmShadowAtTheFrameStays() =
        // Warm light makes a shadow look like skin; judging by colour alone erased a page's left column.
        withWarmLeft({ _, paint ->
            for (i in 0 until 8) {
                paint(30, 170 + i * 34, 130, 174 + i * 34, 30, 22, 14)
                paint(60 + i * 8, 170 + i * 34, 64 + i * 8, 192 + i * 34, 30, 22, 14)
            }
        }) { page ->
            val stroke = lumaAt(page, 80, 240)
            metrics("warmShadow", JSONObject().put("stroke", stroke))
            assertTrue("writing in the shadow was erased: $stroke", stroke < 170.0)
        }

    @Test
    fun anUnwrittenWarmObjectAtTheFrameGoesAndARuleCarriesAcrossIt() =
        withWarmLeft({ _, paint ->
            // A ruled notebook page: rules carry across an object only on ruled paper.
            for (k in 0 until 9) if (k != 4) paint(170, 60 + 60 * k, 780, 62 + 60 * k, 70, 68, 65)
            paint(170, 300, 780, 302, 70, 68, 65) // a rule running up to the object
        }) { page ->
            val object_ = lumaAt(page, 60, 200)
            val beyond = lumaAt(page, 500, 300)
            val across = lumaAt(page, 60, 300)
            metrics("warmObject", JSONObject().put("object", object_).put("rule", beyond).put("across", across))
            assertTrue("the object survived: $object_", object_ >= 245.0)
            assertTrue("the rule was erased: $beyond", beyond < 170.0)
            assertTrue("the rule stopped at the object: $across", across < 200.0)
        }

    @Test
    fun shortShowThroughGoesButAShortPencilDigitStays() =
        mapped(
            1000,
            800,
            {
                // Show-through crisp enough to seed: light all through, about .82 of the paper.
                fill(it, 100, 100, 103, 112, 205)
                fill(it, 110, 104, 118, 106, 205)
                // A pencil digit of the same size: about .68 of the paper.
                fill(it, 300, 100, 303, 112, 170)
                fill(it, 310, 104, 318, 106, 170)
            },
        ) { page ->
            val ghost = mean(page, 100, 102, 103, 110)
            val pencil = mean(page, 300, 102, 303, 110)
            metrics("faintShort", JSONObject().put("ghost", ghost).put("pencil", pencil))
            assertTrue("short show-through stayed: $ghost", ghost > 240.0)
            assertTrue("a pencil digit was erased: $pencil", pencil < 225.0)
        }

    private val printRows = 6
    private val printColumns = 30

    /** Draws soft small print with no large mark anywhere, and returns how many of its letters survive. */
    private fun smallPrintKept(): Int {
        var kept = 0
        mapped(
            1000,
            1400,
            {
                // As in a soft photo of a book: a small dark core inside a wide pale halo, 14 px of core each.
                // A large mark far away, like a heading: with none, nothing counts as isolated at all.
                for (row in 0 until printRows) for (column in 0 until printColumns) {
                    val x = 100 + column * 14
                    val y = 200 + row * 40
                    fill(it, x, y, x + 3, y + 20, 30)
                }
                fill(it, 800, 100, 840, 140, 20)
                org.opencv.imgproc.Imgproc.GaussianBlur(it, it, org.opencv.core.Size(0.0, 0.0), 2.0)
            },
        ) { page ->
            for (row in 0 until printRows) for (column in 0 until printColumns) {
                val x = 100 + column * 14
                val y = 200 + row * 40
                if (mean(page, x, y + 4, x + 3, y + 16) < 200.0) kept++
            }
        }
        return kept
    }

    /**
     * Reported from a real page: small print has no large mark near it, so whole words read as isolated
     * show-through, and "int main(void)" came out as "int main(v".
     */
    @Test
    fun smallPrintFarFromAnyLargeMarkStays() {
        val kept = smallPrintKept()
        metrics("smallPrint", JSONObject().put("kept", kept))
        assertEquals("letters of small print were erased", printRows * printColumns, kept)
    }

    @Test
    fun aFingerTouchingARuleGoesButTheRuleAndARedPenStay() {
        val width = 800
        val height = 600
        val page = Mat(height, width, CvType.CV_8UC3)
        page.setTo(Scalar(240.0, 235.0, 225.0))
        fun paint(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, g: Int, b: Int) {
            val colour = byteArrayOf(r.toByte(), g.toByte(), b.toByte())
            for (y in y0 until y1) for (x in x0 until x1) page.put(y, x, colour)
        }
        paint(40, 300, 760, 302, 70, 68, 65) // a ruled line right across the page
        paint(200, 270, 340, 330, 110, 45, 30) // the dark side of a thumb, on top of the rule
        paint(500, 100, 503, 200, 190, 40, 40) // a red pen stroke
        val field = field(width, height, 235f)
        try {
            ToneZones.apply(page, field, 1f)
            fun luma(x: Int, y: Int) = page.get(y, x).let { .299 * it[0] + .587 * it[1] + .114 * it[2] }
            val finger = luma(270, 285)
            val rule = luma(600, 300)
            val pen = luma(501, 150)
            metrics("finger", JSONObject().put("finger", finger).put("rule", rule).put("pen", pen))
            assertTrue("the finger survived: $finger", finger >= 245.0)
            assertTrue("the rule was erased: $rule", rule < 170.0)
            assertTrue("red pen was erased: $pen", pen < 170.0)
        } finally {
            field.release()
            page.release()
        }
    }

    /** Reported on a real page: under warm light, letters near the edge read as wood and were deleted. */
    @Test
    fun lettersNearTheEdgeSurviveWarmLightButWoodStillGoes() {
        val width = 500
        val height = 500
        val page = Mat(height, width, CvType.CV_8UC3)
        page.setTo(Scalar(235.0, 205.0, 165.0)) // white paper under warm light
        fun paint(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, g: Int, b: Int) {
            val colour = byteArrayOf(r.toByte(), g.toByte(), b.toByte())
            for (y in y0 until y1) for (x in x0 until x1) page.put(y, x, colour)
        }
        // Neutral ink is the paper's colour scaled down, so it is as warm as the paper in raw terms.
        paint(30, 100, 33, 130, 94, 82, 66)
        paint(30, 130, 45, 133, 94, 82, 66)
        // Wood at the same place: warmer than this paper even after balancing it.
        paint(30, 300, 33, 340, 170, 90, 30)
        val field = field(width, height, 205f)
        try {
            ToneZones.apply(page, field, 1f)
            fun luma(x: Int, y: Int) = page.get(y, x).let { .299 * it[0] + .587 * it[1] + .114 * it[2] }
            val letter = luma(31, 115)
            val wood = luma(31, 320)
            metrics("edge-warm", JSONObject().put("letter", letter).put("wood", wood))
            assertTrue("a letter under warm light was erased: $letter", letter < 150.0)
            assertTrue("wood at the edge survived: $wood", wood >= 245.0)
        } finally {
            field.release()
            page.release()
        }
    }

    @Test
    fun strokeEdgeIsAContinuousRampWithoutSteps() {
        val levels = intArrayOf(40, 70, 100, 130, 160, 190, 215)
        mapped(
            460,
            200,
            { page ->
                fill(page, 20, 20, 60, 180, 40)
                for (i in levels.indices) fill(page, 60 + i, 20, 61 + i, 180, levels[i])
            },
        ) { page ->
            val row = bytes(page)
            val edge = IntArray(levels.size) { row[100 * 460 + 60 + it].toInt() and 255 }
            metrics("edge", JSONObject().put("edge", edge.joinToString(",")))
            // The curve is narrow on purpose, as the reference scanner's edges are steeper than ours were, so one pixel
            // may cover most of the way. A hard threshold would jump from ink straight to paper.
            for (i in 1 until edge.size) {
                assertTrue("edge not monotone: ${edge.toList()}", edge[i] >= edge[i - 1])
                assertTrue("edge has a step: ${edge.toList()}", edge[i] - edge[i - 1] <= 180)
            }
            assertTrue("edge has no tone between ink and paper: ${edge.toList()}", edge.any { it in 40..220 })
        }
    }

    @Test
    fun shadingBandMeetsTheBodyWithoutADarkLine() {
        val width = 500
        val boundary = 100
        mapped(width, 300, { fill(it, 0, 0, boundary, 300, 228) }) { page ->
            val band = mean(page, 10, 20, boundary - 20, 280)
            val body = mean(page, boundary + 20, 20, width - 20, 280)
            var darkest = 255
            for (y in 20 until 280) for (x in boundary - 5 until boundary + 5)
                darkest = minOf(darkest, page.get(y, x)[0].toInt() and 255)
            assertTrue("band and body differ: $band vs $body", abs(band - body) < 2.0)
            assertTrue("dark line at the band contour: $darkest", darkest >= 248)
        }
    }

    @Test
    fun strokesInAHardShadowMatchStrokesInTheLightWithNoBoundary() {
        val width = 800
        val height = 600
        val page = page(width, height, 245)
        fill(page, 0, 0, 400, height, 120) // hard shadow on the left half
        // The same 3 px stroke with the same relative profile on both sides.
        fun stroke(x0: Int, paper: Int) {
            fill(page, x0 - 1, 100, x0, 500, (paper * .6).toInt())
            fill(page, x0, 100, x0 + 3, 500, (paper * .2).toInt())
            fill(page, x0 + 3, 100, x0 + 4, 500, (paper * .6).toInt())
        }
        stroke(200, 120)
        stroke(600, 245)
        val gray = Mat().also(page::copyTo)
        val field = cn.paperforge.app.imaging.PaperField.edgePreserving(gray)
        try {
            ToneZones.apply(page, field, 1f)
            val out = bytes(page)
            fun at(x: Int, y: Int) = out[y * width + x].toInt() and 255
            val shadowPaper = mean(page, 40, 40, 360, 90)
            val litPaper = mean(page, 440, 40, 760, 90)
            var darkest = 255
            for (y in 40 until 90) for (x in 380 until 420) darkest = minOf(darkest, at(x, y))
            val shadowWidth = (190 until 215).count { at(it, 300) < 128 }
            val litWidth = (590 until 615).count { at(it, 300) < 128 }
            metrics(
                "shadow",
                JSONObject()
                    .put("shadowPaper", shadowPaper)
                    .put("litPaper", litPaper)
                    .put("darkest", darkest)
                    .put("shadowWidth", shadowWidth)
                    .put("litWidth", litWidth)
            )
            assertTrue("shadow left a paper step: $shadowPaper vs $litPaper", abs(shadowPaper - litPaper) < 2.0)
            assertTrue("boundary line at the shadow edge: $darkest", darkest >= 245)
            assertEquals("stroke in shadow is a different width", litWidth, shadowWidth)
        } finally {
            listOf(page, gray, field).forEach(Mat::release)
        }
    }

    @Test
    fun aLargeSolidMarkIsInkNotShadow() {
        val width = 800
        val page = page(width, 600, 245)
        fill(page, 200, 200, 500, 400, 25) // much wider than the closing kernel
        val gray = Mat().also(page::copyTo)
        val field = cn.paperforge.app.imaging.PaperField.edgePreserving(gray)
        try {
            ToneZones.apply(page, field, 1f)
            val inside = mean(page, 300, 260, 400, 340)
            metrics("solid", JSONObject().put("inside", inside))
            assertTrue("a solid mark was erased as shadow: $inside", inside < 120.0)
        } finally {
            listOf(page, gray, field).forEach(Mat::release)
        }
    }

    @Test
    fun toneZonesCanBeDisabledAndDarkPagesAreRefused() {
        config(JSONObject().put("toneZones", 0)) {
            val page = page(300, 300, 250)
            val field = field(300, 300, 250f)
            try {
                val before = bytes(page)
                assertNull("toneZones=0 must be a no-op", ToneZones.apply(page, field, 1f))
                assertArrayEquals(before, bytes(page))
            } finally {
                field.release()
                page.release()
            }
        }
        val dark = page(300, 300, 120)
        val darkField = field(300, 300, 120f)
        try {
            val stats = ToneZones.apply(dark, darkField, 1f)
            assertNotNull(stats)
            assertFalse("a dark capture must not be pushed to white", stats!!.getBoolean("applied"))
            assertEquals("paper-below-floor", stats.getString("reason"))
            assertEquals(120, dark.get(10, 10)[0].toInt())
        } finally {
            darkField.release()
            dark.release()
        }
    }

    @Test
    fun strengthBlendsBetweenOriginalAndMapped() {
        val page = page(300, 300, 250)
        fill(page, 40, 40, 260, 260, 45)
        val field = field(300, 300, 250f)
        try {
            ToneZones.apply(page, field, 0.5f)
            val ink = mean(page, 70, 70, 230, 230)
            // Source 45, print target 35: half strength lands near 40.
            assertTrue("half strength must sit between source and target: $ink", ink in 37.0..43.0)
        } finally {
            field.release()
            page.release()
        }
    }
}
