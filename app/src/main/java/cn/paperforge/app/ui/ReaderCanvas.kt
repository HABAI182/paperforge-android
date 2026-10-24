package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.paperforge.app.model.EdgeTurnPolicy
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.VisibleTiles
import cn.paperforge.app.pdf.PdfSearch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PageTransform(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f)

val PdfTileCountKey = SemanticsPropertyKey<Int>("PdfTileCount")

@Composable
internal fun ReaderPage(
    session: PdfSession,
    index: Int,
    transform: PageTransform,
    tone: Int,
    paged: Boolean,
    hits: List<PdfSearch.Hit>,
    onTransform: (PageTransform) -> Unit,
    onTap: () -> Unit,
    onLongPress: (PointN) -> Unit = {},
    onLinkTap: (PointN) -> Boolean = { false },
    edgeTurning: Boolean = true,
    onEdgeTurn: (Int) -> Unit = {},
    onEdgeArm: (Int) -> Unit = {},
) {
    val edge = 1600
    val bitmap by
        produceState<Bitmap?>(null, session, index, edge) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { session.render(index, edge) }.getOrNull()
                }
        }
    val current by rememberUpdatedState(transform)
    val change by rememberUpdatedState(onTransform)
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val linkTap by rememberUpdatedState(onLinkTap)
    val edgeEnabled by rememberUpdatedState(edgeTurning)
    val edgeTurn by rememberUpdatedState(onEdgeTurn)
    val edgeArm by rememberUpdatedState(onEdgeArm)
    var armedEdge by remember(index) { mutableIntStateOf(0) }
    var armedAt by remember(index) { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    var tapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastTap by remember { mutableLongStateOf(0L) }
    val b = bitmap
    if (b == null) {
        Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val dimensions = remember(session, index) { session.dimensions(index) }
    val visibleKeys by
        remember(session, index) {
            derivedStateOf {
                VisibleTiles.plan(
                    dimensions.first,
                    dimensions.second,
                    current.zoom,
                    current.x,
                    current.y,
                )
            }
        }
    val tiles by
        produceState(emptyList<PdfTile>(), session, index, visibleKeys) {
            val rendered =
                value
                    .filter { tile ->
                        visibleKeys.any { key ->
                            key.edge == tile.edge &&
                                key.column * 512 == tile.left &&
                                key.row * 512 == tile.top
                        }
                    }
                    .toMutableList()
            value = rendered.toList()
            if (visibleKeys.isNotEmpty()) delay(65)
            for (key in visibleKeys) {
                coroutineContext.ensureActive()
                if (
                    rendered.any {
                        it.edge == key.edge &&
                            it.left == key.column * 512 &&
                            it.top == key.row * 512
                    }
                )
                    continue
                val tile =
                    withContext(Dispatchers.IO) {
                        runCatching { session.tile(index, key.edge, key.column, key.row) }
                            .getOrNull()
                    }
                coroutineContext.ensureActive()
                if (tile != null) {
                    rendered.add(tile)
                    value = rendered.toList()
                }
            }
        }
    BoxWithConstraints(
        if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val ratio = b.width.toFloat() / b.height
        val width = if (paged) minOf(maxWidth, maxHeight * ratio) else maxWidth
        Box(
            Modifier.width(width).aspectRatio(ratio).clipToBounds().pointerInput(session, index) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var eventTime = down.uptimeMillis
                    val initial = current
                    var gesture = current
                    var moved = false
                    var multiple = false
                    var travelled = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        eventTime = event.changes.maxOf { it.uptimeMillis }
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1) multiple = true
                        val delta = event.calculatePan()
                        travelled += delta
                        if (abs(travelled.x) + abs(travelled.y) > viewConfiguration.touchSlop)
                            moved = true
                        if (fingers > 1 || gesture.zoom > 1.01f) {
                            val zoom = (gesture.zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            val factor = zoom / gesture.zoom
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val anchorX =
                                if (centroid.x.isFinite()) centroid.x / size.width - .5f else 0f
                            val anchorY =
                                if (centroid.y.isFinite()) centroid.y / size.height - .5f else 0f
                            val limit = (zoom - 1f) / 2f
                            gesture =
                                PageTransform(
                                    zoom,
                                    (gesture.x * factor +
                                            anchorX * (1 - factor) +
                                            delta.x / size.width)
                                        .coerceIn(-limit, limit),
                                    (gesture.y * factor +
                                            anchorY * (1 - factor) +
                                            delta.y / size.height)
                                        .coerceIn(-limit, limit),
                                )
                            change(gesture)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (moved && edgeEnabled) {
                        val result =
                            EdgeTurnPolicy.release(
                                gesture.zoom,
                                initial.x,
                                gesture.x,
                                travelled.x,
                                travelled.y,
                                armedEdge,
                                eventTime - armedAt in 1..10000,
                                40.dp.toPx(),
                                multiple,
                            )
                        if (result.arm != 0 && result.arm != armedEdge) edgeArm(result.arm)
                        armedEdge = result.arm
                        armedAt = eventTime
                        if (result.flip != 0) edgeTurn(result.flip)
                    } else if (multiple || !edgeEnabled) armedEdge = 0
                    if (!moved && !multiple) {
                        val now = eventTime
                        val point =
                            PointN(
                                ((down.position.x / size.width - .5f - current.x) / current.zoom +
                                        .5f)
                                    .coerceIn(0f, 1f),
                                ((down.position.y / size.height - .5f - current.y) / current.zoom +
                                        .5f)
                                    .coerceIn(0f, 1f),
                            )
                        if (now - down.uptimeMillis >= 500) {
                            tapJob?.cancel()
                            lastTap = 0
                            longPress(point)
                        } else if (now - lastTap < 280) {
                            tapJob?.cancel()
                            change(
                                if (current.zoom > 1.01f) PageTransform()
                                else
                                    PageTransform(
                                        2f,
                                        (.5f - down.position.x / size.width).coerceIn(-.5f, .5f),
                                        (.5f - down.position.y / size.height).coerceIn(-.5f, .5f),
                                    )
                            )
                            armedEdge = 0
                            lastTap = 0
                        } else {
                            lastTap = now
                            tapJob?.cancel()
                            tapJob =
                                scope.launch {
                                    delay(280)
                                    if (!linkTap(point)) tap()
                                }
                        }
                    }
                }
            }
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = transform.zoom
                    scaleY = transform.zoom
                    translationX = transform.x * size.width
                    translationY = transform.y * size.height
                }
            ) {
                val filter =
                    when (tone) {
                        1 ->
                            ColorFilter.colorMatrix(
                                ColorMatrix(
                                    floatArrayOf(
                                        1f,
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        .97f,
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        .88f,
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        1f,
                                        0f,
                                    )
                                )
                            )
                        2 ->
                            ColorFilter.colorMatrix(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f,
                                        0f,
                                        0f,
                                        0f,
                                        245f,
                                        0f,
                                        -1f,
                                        0f,
                                        0f,
                                        245f,
                                        0f,
                                        0f,
                                        -1f,
                                        0f,
                                        245f,
                                        0f,
                                        0f,
                                        0f,
                                        1f,
                                        0f,
                                    )
                                )
                            )
                        else -> null
                    }
                Image(
                    b.asImageBitmap(),
                    "第 ${index + 1} 页",
                    Modifier.fillMaxSize(),
                    colorFilter = filter,
                )
                Canvas(
                    Modifier.fillMaxSize().testTag("reader-tiles-$index").semantics {
                        this[PdfTileCountKey] = tiles.size
                    }
                ) {
                    tiles.forEach { tile ->
                        val left =
                            (tile.left.toFloat() / tile.virtualWidth * size.width).roundToInt()
                        val top =
                            (tile.top.toFloat() / tile.virtualHeight * size.height).roundToInt()
                        val right =
                            ((tile.left + tile.bitmap.width).toFloat() / tile.virtualWidth *
                                    size.width)
                                .roundToInt()
                        val bottom =
                            ((tile.top + tile.bitmap.height).toFloat() / tile.virtualHeight *
                                    size.height)
                                .roundToInt()
                        drawImage(
                            tile.bitmap.asImageBitmap(),
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(tile.bitmap.width, tile.bitmap.height),
                            dstOffset = IntOffset(left, top),
                            dstSize =
                                IntSize(
                                    (right - left).coerceAtLeast(1),
                                    (bottom - top).coerceAtLeast(1),
                                ),
                            colorFilter = filter,
                            filterQuality = FilterQuality.High,
                        )
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    hits.forEach { hit ->
                        drawRect(
                            Color(0x88FFD54F),
                            Offset(hit.left * size.width, hit.top * size.height),
                            Size(
                                (hit.right - hit.left) * size.width,
                                (hit.bottom - hit.top) * size.height,
                            ),
                        )
                    }
                }
            }
        }
    }
}
