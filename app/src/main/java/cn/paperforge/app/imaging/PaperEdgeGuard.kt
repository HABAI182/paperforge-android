package cn.paperforge.app.imaging

import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Removes the thin dirty rim left on the outermost pixels of a cropped/flattened page.
 *
 * Source of the artefact (v200 feedback 4, "corners show grey blotches"): the detected quad sits a
 * pixel or two outside the physical paper, so the destination border is sampled from the table edge
 * or the book spine shadow. Geometric warp keeps those pixels, the paper field treats them as ink,
 * and the workbook protection then keeps them on purpose. The result is a 1-4 pixel grey/dark frame
 * that is invisible at fit size but becomes a grey block in the corner once the page is zoomed.
 *
 * The guard only touches a thin outer ring and only when the darkening does not continue inwards:
 * a real table rule or a character reaching the page edge is dark at the sample distance too, so it
 * survives. Solid ink (luma < [inkFloor]) is never touched. The replacement tone is the local paper
 * level, not pure 255, so the ring does not become a bright 1-pixel line.
 *
 * Colour keeps its ratios: every channel is scaled to the local paper level only when the pixel is
 * low-chroma; a coloured stamp touching the edge is left alone.
 *
 * The whole page is read into a byte plane once and written back once. Per-pixel `Mat.get` on a
 * CV_8UC3 Mat throws `UnsupportedOperationException: Mat data type is not compatible: 16` (the
 * multi-channel `get(row, col, double[])` overload only supports 8U single channel), which is why
 * the first version failed on colour workbook pages.
 */
object PaperEdgeGuard {
    const val VERSION = "paper-edge-guard-2"

    /** Returns the number of rewritten pixels. */
    fun apply(destination: Mat, inkFloor: Int = 48): Int {
        if (ScanConfig.int("paperEdgeGuard", 1) == 0) return 0
        val channels = destination.channels()
        if (channels != 1 && channels != 3) return 0
        if (destination.depth() != CvType.CV_8U || destination.empty()) return 0
        val width = destination.cols()
        val height = destination.rows()
        if (min(width, height) < 160) return 0
        val short = min(width, height)
        val ring =
            max(1, (short * ScanConfig.float("paperEdgeRingFraction", .004f)).roundToInt())
                .coerceAtMost(4)
        val probe = max(ring * 5, 6).coerceAtMost(short / 8)
        val maximumChroma = ScanConfig.float("paperEdgeMaximumChroma", 48f)
        val step = ring * 2
        val stride = width * channels
        val pixels = ByteArray(stride * height)
        destination.get(0, 0, pixels)

        fun value(x: Int, y: Int, channel: Int): Int =
            pixels[y * stride + x * channels + channel].toInt() and 255

        fun luma(x: Int, y: Int): Double {
            val sx = x.coerceIn(0, width - 1)
            val sy = y.coerceIn(0, height - 1)
            return if (channels == 1) value(sx, sy, 0).toDouble()
            else .299 * value(sx, sy, 0) + .587 * value(sx, sy, 1) + .114 * value(sx, sy, 2)
        }

        // v200 20016 regression: a stroke that reaches the page edge must survive. The old guard
        // only checked one pixel at 2*ring inward, so a stroke tip, a rule end, or a stroke whose
        // continuation narrowed was rewritten as if it were a 1-4 px desk rim. Now a pixel is only
        // removable when it is a *plain rim*: not connected to ink that reaches the page interior,
        // not a short high-contrast dark run, and - for a full-edge band - still the uniform rim the
        // feedback was about. The design rule is "keep rim grey before erasing edge ink".
        val contrast =
            ScanConfig.float("paperEdgeInkContrast", 56f).coerceIn(12f, 120f)
        val lumaPlane = ByteArray(width * height)
        val histogram = IntArray(256)
        val sampleStep = max(1, width * height / 40000)
        for (y in 0 until height) for (x in 0 until width) {
            val v = luma(x, y)
            lumaPlane[y * width + x] = v.toInt().coerceIn(0, 255).toByte()
            if ((y * width + x) % sampleStep == 0) histogram[v.toInt().coerceIn(0, 255)]++
        }
        var seen = 0
        var paperLevel = 255
        val target = max(1, (width * height / sampleStep) * 9 / 10)
        for (level in 0..255) {
            seen += histogram[level]
            if (seen >= target) {
                paperLevel = level
                break
            }
        }
        val components = Mat()
        val labels = Mat()
        val stats = Mat()
        val centers = Mat()
        val labelBytes = IntArray(width * height)
        val regions: IntArray
        try {
            val dark = ByteArray(width * height) {
                if (lumaPlane[it].toInt() and 255 < paperLevel - 24) 255.toByte() else 0
            }
            components.create(height, width, CvType.CV_8UC1)
            components.put(0, 0, dark)
            val count =
                Imgproc.connectedComponentsWithStats(
                    components,
                    labels,
                    stats,
                    centers,
                    8,
                    CvType.CV_32S,
                )
            labels.get(0, 0, labelBytes)
            regions = IntArray(count * 5).also { stats.get(0, 0, it) }
        } finally {
            components.release()
            labels.release()
            stats.release()
            centers.release()
        }

        var cleaned = 0
        for (y in 0 until height) {
            val top = y
            val bottom = height - 1 - y
            for (x in 0 until width) {
                val left = x
                val right = width - 1 - x
                val distance = minOf(left, right, top, bottom)
                if (distance >= ring) continue
                // Move inwards along the nearest edge; at a corner either axis is fine.
                var stepX = 0
                var stepY = 0
                if (distance == left) stepX = 1
                else if (distance == right) stepX = -1
                if (stepX == 0) {
                    if (distance == top) stepY = 1 else if (distance == bottom) stepY = -1
                }
                val value = luma(x, y)
                if (value < inkFloor) continue
                if (channels == 3) {
                    val chroma =
                        maxOf(value(x, y, 0), value(x, y, 1), value(x, y, 2)) -
                            minOf(value(x, y, 0), value(x, y, 1), value(x, y, 2))
                    if (chroma > maximumChroma) continue
                }
                // Local paper level just inside the ring: the brightest of a few samples, because a
                // dark rule occupying the whole column must not become the reference.
                var reference = 0.0
                for (offset in intArrayOf(probe, probe + ring, probe + step)) {
                    val sx = (x + stepX * offset).coerceIn(0, width - 1)
                    val sy = (y + stepY * offset).coerceIn(0, height - 1)
                    reference = max(reference, luma(sx, sy))
                }
                if (reference - value < 8.0) continue
                // Paper must resume immediately inside: the darkening is confined to the ring.
                val innerX = (x + stepX * step).coerceIn(0, width - 1)
                val innerY = (y + stepY * step).coerceIn(0, height - 1)
                if (luma(innerX, innerY) < reference - 12.0) continue
                val id = labelBytes[y * width + x]
                if (id > 0) {
                    val x0 = regions[id * 5]
                    val y0 = regions[id * 5 + 1]
                    val cw = regions[id * 5 + 2]
                    val ch = regions[id * 5 + 3]
                    val along = max(cw, ch)
                    val sideLength = if (stepX != 0) height else width
                    // A full-edge uniform band is the artefact the guard exists for: it is dark
                    // along the whole side but never reaches the interior. A page-border ring that
                    // connects the four sides has a page-sized bounding box, so connectivity is
                    // checked locally below rather than from the bbox.
                    val uniformRim = along >= 0.25 * sideLength
                    // Does the darkness continue inward as a connected stroke? Sample a short
                    // perpendicular ray just beyond the "paper resumes" distance. Only strong ink
                    // counts: faint show-through near the edge is exactly what the guard is meant
                    // to lift, and treating it as a stroke would leave grey bands behind.
                    val inwardInk = run {
                        val limit = min(reference - 40.0, 170.0)
                        var hit = false
                        for (d in step + 1..step + 3) {
                            for (o in -2..2) {
                                val sx = if (stepX != 0) x + stepX * d else x + o
                                val sy = if (stepY != 0) y + stepY * d else y + o
                                if (luma(sx, sy) < limit) {
                                    hit = true
                                    break
                                }
                            }
                            if (hit) break
                        }
                        hit
                    }
                    // Ink that reaches the interior (a stroke, a rule, a glyph) is never a rim.
                    if (inwardInk) continue
                    // A short, clearly dark run at the very edge is a stroke tip or a rule end.
                    // Only a low-contrast or full-edge uniform ring is removable.
                    if (paperLevel - value >= contrast && !uniformRim) continue
                }
                // Scale every channel to the local paper level so hue is preserved.
                val scale = reference / max(1.0, value)
                for (c in 0 until channels) {
                    val lifted = (value(x, y, c) * scale).roundToInt().coerceIn(0, 255)
                    pixels[y * stride + x * channels + c] = lifted.toByte()
                }
                cleaned++
            }
        }
        if (cleaned > 0) destination.put(0, 0, pixels)
        return cleaned
    }
}
