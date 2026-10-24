package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.paperforge.app.model.*
import cn.paperforge.app.pdf.PdfSearch
import cn.paperforge.app.pdf.ReaderImages
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
    viewport: PageViewport = PageViewport.Full,
    fitWidth: Boolean = false,
    initializeAtTop: Boolean = false,
    preserveImageColor: Boolean = true,
) {
    val bitmap by
        produceState<Bitmap?>(null, session, index) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { session.render(index, 1600) }.getOrNull()
                }
        }
    val current by rememberUpdatedState(transform)
    val currentViewport by rememberUpdatedState(viewport)
    val widthFit by rememberUpdatedState(fitWidth)
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
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val b = bitmap
    if (b == null) {
        Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val dimensions = remember(session, index) { session.dimensions(index) }
    val imageRegions by
        produceState(emptyList<List<PointN>>(), session, index, preserveImageColor, tone == 0) {
            value =
                if (preserveImageColor && tone != 0)
                    withContext(Dispatchers.IO) {
                        runCatching { ReaderImages.regions(session.file, index) }
                            .getOrDefault(emptyList())
                    }
                else emptyList()
        }
    val ratio = dimensions.first.toFloat() * viewport.width / (dimensions.second * viewport.height)
    val frame =
        ReadingFrame.fit(rootSize.width.toFloat(), rootSize.height.toFloat(), ratio, fitWidth)
    LaunchedEffect(frame, viewport, fitWidth) {
        if (rootSize.width > 0)
            change(
                current.copy(
                    x = current.x.coerceIn(-frame.limitX(current.zoom), frame.limitX(current.zoom)),
                    y =
                        (if (initializeAtTop && fitWidth) frame.limitY(current.zoom) else current.y)
                            .coerceIn(-frame.limitY(current.zoom), frame.limitY(current.zoom)),
                )
            )
    }
    val visibleKeys =
        VisibleTiles.plan(
            dimensions.first,
            dimensions.second,
            transform.zoom,
            transform.x,
            transform.y,
            viewport,
            frame.viewportWidth / frame.pageWidth,
            frame.viewportHeight / frame.pageHeight,
        )
    val tiles by
        produceState(emptyList<PdfTile>(), session, index, visibleKeys) {
            val rendered =
                value
                    .filter { tile ->
                        visibleKeys.any {
                            it.edge == tile.edge &&
                                it.column * 512 == tile.left &&
                                it.row * 512 == tile.top
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
    val painter =
        remember(b, viewport) {
            val left = (viewport.left * b.width).roundToInt().coerceIn(0, b.width - 1)
            val top = (viewport.top * b.height).roundToInt().coerceIn(0, b.height - 1)
            BitmapPainter(
                b.asImageBitmap(),
                IntOffset(left, top),
                IntSize(
                    ((viewport.right * b.width).roundToInt() - left).coerceIn(1, b.width - left),
                    ((viewport.bottom * b.height).roundToInt() - top).coerceIn(1, b.height - top),
                ),
                FilterQuality.High,
            )
        }
    // Clip the viewport, not the unscaled fit-page box: zoom then uses the whole screen.
    BoxWithConstraints(
        (if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(ratio))
            .testTag("reader-viewport-$index")
            .onSizeChanged { rootSize = it }
            .clipToBounds()
            .pointerInput(session, index) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val view = currentViewport
                    val pageRatio =
                        dimensions.first.toFloat() * view.width / (dimensions.second * view.height)
                    val geometry =
                        ReadingFrame.fit(
                            size.width.toFloat(),
                            size.height.toFloat(),
                            pageRatio,
                            widthFit,
                        )
                    val initial = current
                    var gesture = initial
                    var eventTime = down.uptimeMillis
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
                        val verticalFit =
                            widthFit &&
                                geometry.limitY(gesture.zoom) > 0 &&
                                abs(travelled.y) > abs(travelled.x)
                        if (fingers > 1 || gesture.zoom > 1.01f || (moved && verticalFit)) {
                            val zoom = (gesture.zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            val factor = zoom / gesture.zoom
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val anchorX =
                                if (centroid.x.isFinite())
                                    (centroid.x - size.width / 2f) / geometry.pageWidth
                                else 0f
                            val anchorY =
                                if (centroid.y.isFinite())
                                    (centroid.y - size.height / 2f) / geometry.pageHeight
                                else 0f
                            val lx = geometry.limitX(zoom)
                            val ly = geometry.limitY(zoom)
                            gesture =
                                PageTransform(
                                    zoom,
                                    (gesture.x * factor +
                                            anchorX * (1 - factor) +
                                            delta.x / geometry.pageWidth)
                                        .coerceIn(-lx, lx),
                                    (gesture.y * factor +
                                            anchorY * (1 - factor) +
                                            delta.y / geometry.pageHeight)
                                        .coerceIn(-ly, ly),
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
                                geometry.limitX(gesture.zoom),
                            )
                        if (result.arm != 0 && result.arm != armedEdge) edgeArm(result.arm)
                        armedEdge = result.arm
                        armedAt = eventTime
                        if (result.flip != 0) edgeTurn(result.flip)
                    } else if (multiple || !edgeEnabled) armedEdge = 0
                    if (!moved && !multiple) {
                        val point =
                            view.source(
                                geometry.point(
                                    down.position.x,
                                    down.position.y,
                                    current.zoom,
                                    current.x,
                                    current.y,
                                )
                            )
                        val inside =
                            point.x in view.left..view.right && point.y in view.top..view.bottom
                        if (eventTime - down.uptimeMillis >= 500) {
                            tapJob?.cancel()
                            lastTap = 0
                            if (inside) longPress(point)
                        } else if (eventTime - lastTap < 280) {
                            tapJob?.cancel()
                            if (current.zoom > 1.01f) change(PageTransform())
                            else {
                                val lx = geometry.limitX(2f)
                                val ly = geometry.limitY(2f)
                                change(
                                    PageTransform(
                                        2f,
                                        (-(down.position.x - size.width / 2f) / geometry.pageWidth +
                                                2 * current.x)
                                            .coerceIn(-lx, lx),
                                        (-(down.position.y - size.height / 2f) /
                                                geometry.pageHeight + 2 * current.y)
                                            .coerceIn(-ly, ly),
                                    )
                                )
                            }
                            armedEdge = 0
                            lastTap = 0
                        } else {
                            lastTap = eventTime
                            tapJob?.cancel()
                            tapJob =
                                scope.launch {
                                    delay(280)
                                    if (!inside || !linkTap(point)) tap()
                                }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val width = if (fitWidth) maxWidth else minOf(maxWidth, maxHeight * ratio)
        Box(Modifier.requiredWidth(width).requiredHeight(width / ratio)) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.zoom
                        scaleY = transform.zoom
                        translationX = transform.x * size.width
                        translationY = transform.y * size.height
                    }
                    .clipToBounds()
            ) {
                val filter = readerToneFilter(tone)
                Image(painter, "第 ${index+1} 页", Modifier.fillMaxSize(), colorFilter = filter)
                Canvas(
                    Modifier.fillMaxSize().testTag("reader-tiles-$index").semantics {
                        this[PdfTileCountKey] = tiles.size
                    }
                ) {
                    fun drawTiles(color: ColorFilter?) {
                        tiles.forEach { tile ->
                            val start =
                                viewport.view(
                                    PointN(
                                        tile.left.toFloat() / tile.virtualWidth,
                                        tile.top.toFloat() / tile.virtualHeight,
                                    )
                                )
                            val end =
                                viewport.view(
                                    PointN(
                                        (tile.left + tile.bitmap.width).toFloat() /
                                            tile.virtualWidth,
                                        (tile.top + tile.bitmap.height).toFloat() /
                                            tile.virtualHeight,
                                    )
                                )
                            val left = (start.x * size.width).roundToInt()
                            val top = (start.y * size.height).roundToInt()
                            val right = (end.x * size.width).roundToInt()
                            val bottom = (end.y * size.height).roundToInt()
                            drawImage(
                                tile.bitmap.asImageBitmap(),
                                IntOffset.Zero,
                                IntSize(tile.bitmap.width, tile.bitmap.height),
                                IntOffset(left, top),
                                IntSize(
                                    (right - left).coerceAtLeast(1),
                                    (bottom - top).coerceAtLeast(1),
                                ),
                                colorFilter = color,
                                filterQuality = FilterQuality.High,
                            )
                        }
                    }
                    drawTiles(filter)
                    if (imageRegions.isNotEmpty()) {
                        val path =
                            Path().apply {
                                imageRegions.forEach { polygon ->
                                    polygon.forEachIndexed { i, p ->
                                        val v = viewport.view(p)
                                        if (i == 0) moveTo(v.x * size.width, v.y * size.height)
                                        else lineTo(v.x * size.width, v.y * size.height)
                                    }
                                    close()
                                }
                            }
                        clipPath(path) {
                            val left =
                                (viewport.left * b.width).roundToInt().coerceIn(0, b.width - 1)
                            val top =
                                (viewport.top * b.height).roundToInt().coerceIn(0, b.height - 1)
                            drawImage(
                                b.asImageBitmap(),
                                IntOffset(left, top),
                                IntSize(
                                    ((viewport.right * b.width).roundToInt() - left).coerceIn(
                                        1,
                                        b.width - left,
                                    ),
                                    ((viewport.bottom * b.height).roundToInt() - top).coerceIn(
                                        1,
                                        b.height - top,
                                    ),
                                ),
                                dstSize =
                                    IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                filterQuality = FilterQuality.High,
                            )
                            drawTiles(null)
                        }
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    hits.forEach { hit ->
                        val p = viewport.view(PointN(hit.left, hit.top))
                        drawRect(
                            Color(0x88FFD54F),
                            Offset(p.x * size.width, p.y * size.height),
                            Size(
                                (hit.right - hit.left) / viewport.width * size.width,
                                (hit.bottom - hit.top) / viewport.height * size.height,
                            ),
                        )
                    }
                }
            }
        }
    }
}

internal fun readerToneFilter(tone: Int): ColorFilter? =
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
