package cn.paperforge.app.pdf

import android.graphics.*
import cn.paperforge.app.model.*
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.text.Normalizer

object NativeTextCoverage {
    const val VERSION = "native-regions-images-unicode-2"

    data class Region(val text: String, val rectangle: RectF)

    data class Assessment(
        val regions: List<Region>,
        val images: List<RectF>,
        val badGlyphs: Int,
        val needsOcr: Boolean,
        val reason: String,
    )

    fun validText(text: String?) =
        !text.isNullOrEmpty() &&
            text.codePoints().allMatch { cp ->
                cp != 0xfffd &&
                    !Character.isISOControl(cp) &&
                    Character.getType(cp) !in
                        setOf(
                            Character.PRIVATE_USE.toInt(),
                            Character.UNASSIGNED.toInt(),
                            Character.SURROGATE.toInt(),
                        )
            }

    fun valid(position: TextPosition) =
        validText(position.unicode) &&
            position.widthDirAdj.isFinite() &&
            position.heightDir.isFinite() &&
            position.heightDir > 0

    fun assess(file: File, index: Int): Assessment = PDDocument.load(file).use { assess(it, index) }

    fun brokenFonts(doc: PDDocument, index: Int): Set<COSDictionary> {
        val fonts = mutableSetOf<COSDictionary>()
        val stripper =
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    if (!valid(text)) fonts += text.font.cosObject
                }
            }
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.getText(doc)
        return fonts
    }

    fun assess(doc: PDDocument, index: Int): Assessment {
        val native = regions(doc, index)
        var bad = 0
        val scanner =
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    if (!valid(text)) bad++
                }
            }
        scanner.startPage = index + 1
        scanner.endPage = index + 1
        scanner.getText(doc)
        val page = doc.getPage(index)
        val box = page.cropBox
        val images = mutableListOf<RectF>()
        var point = PointF()
        fun display(p: PointF): PointF {
            val x = (p.x - box.lowerLeftX) / box.width
            val y = 1 - (p.y - box.lowerLeftY) / box.height
            return when ((page.rotation % 360 + 360) % 360) {
                90 -> PointF(1 - y, x)
                180 -> PointF(1 - x, 1 - y)
                270 -> PointF(y, 1 - x)
                else -> PointF(x, y)
            }
        }
        val graphics =
            object : PDFGraphicsStreamEngine(page) {
                override fun drawImage(image: PDImage) {
                    val m = graphicsState.currentTransformationMatrix
                    val points =
                        listOf(
                                m.transformPoint(0f, 0f),
                                m.transformPoint(1f, 0f),
                                m.transformPoint(1f, 1f),
                                m.transformPoint(0f, 1f),
                            )
                            .map(::display)
                    val r =
                        RectF(
                            points.minOf { it.x }.coerceIn(0f, 1f),
                            points.minOf { it.y }.coerceIn(0f, 1f),
                            points.maxOf { it.x }.coerceIn(0f, 1f),
                            points.maxOf { it.y }.coerceIn(0f, 1f),
                        )
                    if (r.width() * r.height() > .08f) images += r
                }

                override fun appendRectangle(a: PointF, b: PointF, c: PointF, d: PointF) {
                    point = a
                }

                override fun clip(rule: Path.FillType) {}

                override fun moveTo(x: Float, y: Float) {
                    point = PointF(x, y)
                }

                override fun lineTo(x: Float, y: Float) {
                    point = PointF(x, y)
                }

                override fun curveTo(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
                    point = PointF(e, f)
                }

                override fun getCurrentPoint() = point

                override fun closePath() {}

                override fun endPath() {}

                override fun strokePath() {}

                override fun fillPath(rule: Path.FillType) {}

                override fun fillAndStrokePath(rule: Path.FillType) {}

                override fun shadingFill(name: COSName) {}
            }
        val parsed = runCatching { graphics.processPage(page) }.isSuccess
        val uncovered =
            images.any { image ->
                val inside =
                    native.filter { image.contains(it.rectangle.centerX(), it.rectangle.centerY()) }
                val chars = inside.sumOf { it.text.count { c -> !c.isWhitespace() } }
                // Header and footer alone can span the page while the body is still a scan.
                val occupiedBands =
                    (0 until 20).count { band ->
                        val top = image.top + image.height() * band / 20
                        val bottom = image.top + image.height() * (band + 1) / 20
                        inside.any { it.rectangle.bottom > top && it.rectangle.top < bottom }
                    }
                chars < 60 || occupiedBands < 8
            }
        val need = native.isEmpty() || bad > 0 || uncovered || !parsed
        return Assessment(
            native,
            images,
            bad,
            need,
            when {
                bad > 0 -> "unreliable-unicode"
                native.isEmpty() -> "no-readable-native-text"
                uncovered -> "uncovered-image-region"
                !parsed -> "image-analysis-incomplete"
                else -> "native-text"
            },
        )
    }

    fun regions(doc: PDDocument, index: Int): List<Region> {
        val page = doc.getPage(index)
        val box = page.cropBox
        val regions = mutableListOf<Region>()
        val stripper =
            object : ReadablePdfTextStripper(doc) {
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    if (positions.isEmpty()) return
                    val points =
                        positions.flatMap { p -> PdfGlyphGeometry.displayed(p, box, page.rotation) }
                    regions.add(
                        Region(
                            positions.joinToString("") { it.unicode },
                            RectF(
                                points.minOf { it.x },
                                points.minOf { it.y },
                                points.maxOf { it.x },
                                points.maxOf { it.y },
                            ),
                        )
                    )
                }
            }
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.sortByPosition = true
        stripper.getText(doc)
        return regions
    }

    fun substantial(regions: List<Region>) =
        regions.sumOf { it.text.count { c -> !c.isWhitespace() } } >= 120 &&
            regions.sumOf { (it.rectangle.width() * it.rectangle.height()).toDouble() } > .1

    private fun normalized(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFKC).filterNot { it.isWhitespace() }

    private fun covered(text: String, points: List<PointN>, regions: List<Region>): Boolean {
        if (points.isEmpty()) return false
        val r =
            RectF(
                points.minOf { it.x },
                points.minOf { it.y },
                points.maxOf { it.x },
                points.maxOf { it.y },
            )
        if (r.width() * r.height() <= 0) return false
        return regions.any { native ->
            val intersection = RectF(r)
            intersection.intersect(native.rectangle) &&
                intersection.width() * intersection.height() > r.width() * r.height() * .55f &&
                normalized(text).isNotEmpty() &&
                normalized(native.text).contains(normalized(text))
        }
    }

    fun removeCovered(layout: OcrLayout, regions: List<Region>): OcrLayout =
        layout.copy(
            lines =
                layout.lines.flatMap { line ->
                    if (covered(line.text, line.corners, regions)) emptyList()
                    else {
                        val elements = line.elements
                        val complete =
                            elements.isNotEmpty() &&
                                normalized(elements.joinToString("") { it.text }) ==
                                    normalized(line.text)
                        if (!complete || elements.none { covered(it.text, it.corners, regions) })
                            listOf(line)
                        else
                            elements
                                .filterNot { covered(it.text, it.corners, regions) }
                                .map { word ->
                                    line.copy(
                                        text = word.text,
                                        corners = word.corners,
                                        confidence = word.confidence,
                                        angle = word.angle,
                                        elements = listOf(word),
                                    )
                                }
                    }
                }
        )
}
