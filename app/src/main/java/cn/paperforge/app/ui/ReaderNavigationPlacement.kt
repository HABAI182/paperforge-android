package cn.paperforge.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import cn.paperforge.app.ReaderPosition
import cn.paperforge.app.model.PageViewport
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.ReadingFrame
import cn.paperforge.app.pdf.ReaderDestination
import org.json.JSONArray

internal data class ReaderNavigationRequest(
    val target: ReaderDestination,
    val origin: ReaderPosition,
)

internal data class ReaderPlacement(val transform: PageTransform, val offset: Int = 0)

internal val ReaderHistorySaver =
    listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<ReaderPosition>, String>(
        save = { list ->
            list.map {
                JSONArray(
                        listOf(
                            it.page,
                            it.horizontal,
                            it.zoom,
                            it.panX,
                            it.panY,
                            it.offset,
                            it.tone,
                        )
                    )
                    .toString()
            }
        },
        restore = { list ->
            mutableStateListOf<ReaderPosition>().apply {
                list.forEach { text ->
                    runCatching { JSONArray(text) }
                        .getOrNull()
                        ?.let {
                            add(
                                ReaderPosition(
                                    it.getInt(0),
                                    it.getBoolean(1),
                                    it.getDouble(2).toFloat(),
                                    it.getDouble(3).toFloat(),
                                    it.getDouble(4).toFloat(),
                                    it.getInt(5),
                                    it.getInt(6),
                                )
                            )
                        }
                }
            }
        },
    )

/**
 * Bounded placement in the existing page layout. Continuous mode scrolls the item to the anchor.
 */
internal fun placeReaderDestination(
    request: ReaderNavigationRequest,
    frame: ReadingFrame,
    viewport: PageViewport,
    pageWidth: Float,
    density: Float,
    paged: Boolean,
    visibleHeight: Float,
): ReaderPlacement {
    val t = request.target
    val origin = request.origin
    val prior = PageTransform(origin.zoom, origin.panX, origin.panY)
    val oldCorner =
        frame.point(0f, if (paged) 0f else origin.offset.toFloat(), prior.zoom, prior.x, prior.y)
    val x = t.x?.let { viewport.view(PointN(it, 0f)).x } ?: oldCorner.x
    val y = t.y?.let { viewport.view(PointN(0f, it)).y } ?: oldCorner.y
    val zoom =
        when (t.fit) {
            "XYZ" ->
                t.zoom?.let { it * density * pageWidth * viewport.width / frame.pageWidth }
                    ?: prior.zoom
            "FitH" -> frame.viewportWidth / frame.pageWidth
            "FitV" -> visibleHeight / frame.pageHeight
            "FitR" ->
                minOf(
                    frame.viewportWidth /
                        (frame.pageWidth *
                            (((t.right ?: 1f) - (t.x ?: 0f)) / viewport.width).coerceAtLeast(
                                .001f
                            )),
                    visibleHeight /
                        (frame.pageHeight *
                            (((t.bottom ?: 1f) - (t.y ?: 0f)) / viewport.height).coerceAtLeast(
                                .001f
                            )),
                )
            "Fit" -> minOf(frame.viewportWidth / frame.pageWidth, visibleHeight / frame.pageHeight)
            else -> prior.zoom
        }.coerceIn(1f, 5f)
    val rect = t.fit == "FitR"
    val centerX = if (rect) viewport.view(PointN(((t.x ?: 0f) + (t.right ?: 1f)) / 2, 0f)).x else x
    val centerY = if (rect) viewport.view(PointN(0f, ((t.y ?: 0f) + (t.bottom ?: 1f)) / 2)).y else y
    val px =
        when {
            t.fit == "Fit" || t.fit == "FitH" -> 0f
            rect -> (.5f - centerX) * zoom
            else -> -frame.viewportWidth / (2 * frame.pageWidth) + (.5f - x) * zoom
        }.coerceIn(-frame.limitX(zoom), frame.limitX(zoom))
    val py =
        if (!paged) 0f
        else
            when {
                t.fit == "Fit" || t.fit == "FitV" -> 0f
                rect -> (.5f - centerY) * zoom
                else -> -frame.viewportHeight / (2 * frame.pageHeight) + (.5f - y) * zoom
            }.coerceIn(-frame.limitY(zoom), frame.limitY(zoom))
    val offset =
        if (paged || t.fit == "Fit") 0
        else if (t.y == null && t.fit != "FitR") origin.offset
        else
            ((frame.pageHeight / 2 + (centerY - .5f) * zoom * frame.pageHeight) -
                    (if (rect) visibleHeight / 2 else 0f))
                .toInt()
                .coerceIn(0, (frame.pageHeight - 1).toInt().coerceAtLeast(0))
    return ReaderPlacement(PageTransform(zoom, px, py), offset)
}
