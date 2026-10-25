package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*

/** Coordinates use the rotated CropBox, just like the displayed PDF page. Null axes stay null. */
data class ReaderDestination(
    val page: Int,
    val fit: String = "Keep",
    val x: Float? = null,
    val y: Float? = null,
    val right: Float? = null,
    val bottom: Float? = null,
    val zoom: Float? = null,
)

internal fun readerDestination(doc: PDDocument, value: PDDestination?): ReaderDestination? {
    val actual =
        when (value) {
            is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(value)
            is PDPageDestination -> value
            else -> null
        } ?: return null
    val index =
        actual.page?.let { doc.pages.indexOf(it) }?.takeIf { it >= 0 }
            ?: actual.pageNumber.takeIf { it in 0 until doc.numberOfPages }
            ?: return null
    val page = doc.getPage(index)
    val crop = page.cropBox
    val array = actual.cosObject
    fun number(i: Int) =
        (if (i < array.size()) array.getObject(i) as? COSNumber else null)?.floatValue()?.takeIf {
            it.isFinite()
        }
    fun x(i: Int) = number(i)?.let { (it - crop.lowerLeftX) / crop.width }
    fun y(i: Int) = number(i)?.let { 1 - (it - crop.lowerLeftY) / crop.height }
    val rotation = (page.rotation % 360 + 360) % 360
    fun rotate(x: Float?, y: Float?): Pair<Float?, Float?> =
        when (rotation) {
            90 -> y?.let { 1 - it } to x
            180 -> x?.let { 1 - it } to y?.let { 1 - it }
            270 -> y to x?.let { 1 - it }
            else -> x to y
        }
    val type = (array.getObject(1) as? COSName)?.name ?: "Fit"
    return when (type) {
        "XYZ" -> {
            val (left, top) = rotate(x(2), y(3))
            ReaderDestination(index, type, left, top, zoom = number(4)?.takeIf { it > 0 })
        }
        "FitH",
        "FitBH" -> {
            val (left, top) = rotate(null, y(2))
            ReaderDestination(index, if (rotation % 180 == 0) "FitH" else "FitV", left, top)
        }
        "FitV",
        "FitBV" -> {
            val (left, top) = rotate(x(2), null)
            ReaderDestination(index, if (rotation % 180 == 0) "FitV" else "FitH", left, top)
        }
        "FitR" -> {
            val points = listOf(rotate(x(2), y(3)), rotate(x(4), y(5)))
            if (points.any { it.first == null || it.second == null })
                ReaderDestination(index, "Fit")
            else
                ReaderDestination(
                    index,
                    type,
                    points.minOf { it.first!! },
                    points.minOf { it.second!! },
                    points.maxOf { it.first!! },
                    points.maxOf { it.second!! },
                )
        }
        else -> ReaderDestination(index, "Fit")
    }
}
