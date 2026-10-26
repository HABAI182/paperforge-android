package cn.paperforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.ReaderTextSelection
import kotlin.math.roundToInt

internal fun selectionTip(
    point: PointN,
    frame: ReadingFrame,
    view: PageViewport,
    transform: PageTransform,
): Offset {
    val p = view.view(point)
    return Offset(
        frame.viewportWidth / 2 +
            (p.x - .5f) * frame.pageWidth * transform.zoom +
            transform.x * frame.pageWidth,
        frame.viewportHeight / 2 +
            (p.y - .5f) * frame.pageHeight * transform.zoom +
            transform.y * frame.pageHeight,
    )
}

@Composable
internal fun ReaderSelectionHandles(
    selection: ReaderTextSelection,
    frame: ReadingFrame,
    viewport: PageViewport,
    transform: PageTransform,
    onChange: (Boolean, PointN) -> Unit,
) {
    val color = MaterialTheme.colorScheme.primary
    val radius = with(LocalDensity.current) { 24.dp.toPx() }
    val currentFrame by rememberUpdatedState(frame)
    val currentView by rememberUpdatedState(viewport)
    val currentTransform by rememberUpdatedState(transform)
    val change by rememberUpdatedState(onChange)
    listOf(true to selection.startPoint, false to selection.endPoint).forEach { (start, point) ->
        val tip = selectionTip(point, frame, viewport, transform)
        val latestTip by rememberUpdatedState(tip)
        if (
            tip.x in -radius..(frame.viewportWidth + radius) &&
                tip.y in -radius..(frame.viewportHeight + radius)
        )
            Canvas(
                Modifier.offset {
                        IntOffset((tip.x - radius).roundToInt(), (tip.y - radius / 2).roundToInt())
                    }
                    .size(48.dp)
                    .testTag(if (start) "reader-selection-start" else "reader-selection-end")
                    .semantics { contentDescription = if (start) "拖动选择起点" else "拖动选择终点" }
                    .pointerInput(selection.model.page, start) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var drag = latestTip
                            do {
                                val event = awaitPointerEvent()
                                val pointer =
                                    event.changes.firstOrNull { it.id == down.id } ?: break
                                drag += pointer.positionChange()
                                pointer.consume()
                                val t = currentTransform
                                change(
                                    start,
                                    currentView.source(
                                        currentFrame.point(drag.x, drag.y, t.zoom, t.x, t.y)
                                    ),
                                )
                            } while (pointer.pressed)
                        }
                    }
            ) {
                val x = size.width / 2
                val y = size.height / 4
                drawLine(
                    color,
                    Offset(x, y - 8.dp.toPx()),
                    Offset(x, y + 10.dp.toPx()),
                    2.dp.toPx(),
                )
                drawPath(
                    Path().apply {
                        moveTo(x, y)
                        if (start) {
                            cubicTo(
                                x - 22.dp.toPx(),
                                y + 10.dp.toPx(),
                                x - 10.dp.toPx(),
                                y + 29.dp.toPx(),
                                x,
                                y + 13.dp.toPx(),
                            )
                        } else
                            cubicTo(
                                x + 22.dp.toPx(),
                                y + 10.dp.toPx(),
                                x + 10.dp.toPx(),
                                y + 29.dp.toPx(),
                                x,
                                y + 13.dp.toPx(),
                            )
                        close()
                    },
                    color,
                )
            }
    }
}
