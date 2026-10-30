package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
val PdfViewTransformKey = SemanticsPropertyKey<List<Float>>("PdfViewTransform")

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
    onReadText: (() -> Unit)? = null,
    onLinkTap: (PointN) -> Boolean = { false },
    edgeTurning: Boolean = true,
    onEdgeTurn: (Int) -> Unit = {},
    onEdgeArm: (Int) -> Unit = {},
    viewport: PageViewport = PageViewport.Full,
    fitWidth: Boolean = false,
    initializeAtTop: Boolean = false,
    preserveImageColor: Boolean = true,
    navigationRequest: ReaderNavigationRequest? = null,
    visibleHeight: Float = 0f,
    onNavigated: (ReaderPlacement) -> Unit = {},
    selection: cn.paperforge.app.pdf.ReaderTextSelection? = null,
    onSelectionMove: (Boolean, PointN) -> Unit = { _, _ -> },
    regionPicking: Boolean = false,
    regionDraft: OcrRegion? = null,
    onRegionSelection: (OcrRegion, Boolean) -> Unit = { _, _ -> },
) {
    var renderAttempt by remember(session, index) { mutableIntStateOf(0) }
    val preview by
        produceState<Result<Bitmap>?>(null, session, index, renderAttempt) {
            value = null
            value = withContext(Dispatchers.IO) { runCatching { session.render(index, 1600) } }
        }
    val picking by rememberUpdatedState(regionPicking)
    val pick by rememberUpdatedState(onRegionSelection)
    val currentSelection by rememberUpdatedState(selection)
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
    val b = preview?.getOrNull()
    if (b == null) {
        Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
            if (preview?.isFailure == true)
                PaperEmptyState(
                    Icons.Default.Description,
                    "这一页暂时无法显示",
                    "可重试预览，或通过页面面板查看其他页。",
                    "重试预览",
                    { renderAttempt++ },
                )
            else CircularProgressIndicator()
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
    val density = LocalDensity.current.density
    LaunchedEffect(navigationRequest, frame, viewport, visibleHeight) {
        if (navigationRequest != null && rootSize.width > 0 && visibleHeight > 0) {
            onNavigated(
                placeReaderDestination(
                    navigationRequest,
                    frame,
                    viewport,
                    dimensions.first.toFloat(),
                    density,
                    paged,
                    visibleHeight,
                )
            )
        }
    }
    LaunchedEffect(frame, viewport, fitWidth) {
        if (rootSize.width > 0 && navigationRequest == null)
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
            .semantics {
                contentDescription = "第 ${index+1} 页"
                role = Role.Image
                this[PdfViewTransformKey] = listOf(transform.zoom, transform.x, transform.y)
                stateDescription = "第 ${index+1} 页，缩放 ${(transform.zoom*100).toInt()}%"
                customActions = buildList {
                    add(
                        CustomAccessibilityAction("放大页面") {
                            change(current.copy(zoom = (current.zoom * 1.5f).coerceAtMost(5f)))
                            true
                        }
                    )
                    add(
                        CustomAccessibilityAction("缩小页面") {
                            val zoom = (current.zoom / 1.5f).coerceAtLeast(1f)
                            change(
                                PageTransform(
                                    zoom,
                                    current.x.coerceIn(-frame.limitX(zoom), frame.limitX(zoom)),
                                    current.y.coerceIn(-frame.limitY(zoom), frame.limitY(zoom)),
                                )
                            )
                            true
                        }
                    )
                    if (current.zoom > 1f) {
                        fun move(x: Float, y: Float): Boolean {
                            change(
                                current.copy(
                                    x =
                                        (current.x + x).coerceIn(
                                            -frame.limitX(current.zoom),
                                            frame.limitX(current.zoom),
                                        ),
                                    y =
                                        (current.y + y).coerceIn(
                                            -frame.limitY(current.zoom),
                                            frame.limitY(current.zoom),
                                        ),
                                )
                            )
                            return true
                        }
                        add(CustomAccessibilityAction("向左移动页面") { move(-.15f, 0f) })
                        add(CustomAccessibilityAction("向右移动页面") { move(.15f, 0f) })
                        add(CustomAccessibilityAction("向上移动页面") { move(0f, -.15f) })
                        add(CustomAccessibilityAction("向下移动页面") { move(0f, .15f) })
                    }
                    if (index > 0)
                        add(
                            CustomAccessibilityAction("上一页") {
                                edgeTurn(-1)
                                true
                            }
                        )
                    if (index + 1 < session.count)
                        add(
                            CustomAccessibilityAction("下一页") {
                                edgeTurn(1)
                                true
                            }
                        )
                    add(
                        CustomAccessibilityAction("重置缩放") {
                            change(PageTransform())
                            true
                        }
                    )
                    onReadText?.let { read ->
                        add(
                            CustomAccessibilityAction("读取本页文字") {
                                read()
                                true
                            }
                        )
                    }
                }
                onClick("显示或隐藏阅读工具") {
                    tap()
                    true
                }
            }
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
                    if (picking) {
                        down.consume()
                        fun source(offset: Offset) =
                            view.source(
                                geometry.point(
                                    offset.x,
                                    offset.y,
                                    initial.zoom,
                                    initial.x,
                                    initial.y,
                                )
                            )
                        val start = source(down.position)
                        do {
                            val event = awaitPointerEvent()
                            val end = source(event.changes.first().position)
                            val region = OcrRegion.between(start, end)
                            val complete = event.changes.none { it.pressed }
                            event.changes.forEach { it.consume() }
                            pick(region, complete)
                        } while (!complete)
                        return@awaitEachGesture
                    }
                    val selected = currentSelection
                    if (
                        selected != null &&
                            listOf(selected.startPoint, selected.endPoint).any {
                                val tip = selectionTip(it, geometry, view, initial)
                                (tip - down.position).getDistance() < 32.dp.toPx()
                            }
                    )
                        return@awaitEachGesture
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
                        } else if (lastTap > 0 && eventTime - lastTap < 280) {
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
                Image(painter, null, Modifier.fillMaxSize(), colorFilter = filter)
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
                    regionDraft?.let { region ->
                        val p = viewport.view(PointN(region.left, region.top))
                        drawRect(
                            Color(0x6644B8D6),
                            Offset(p.x * size.width, p.y * size.height),
                            Size(
                                region.width / viewport.width * size.width,
                                region.height / viewport.height * size.height,
                            ),
                        )
                    }
                    selection?.blocks?.forEach { block ->
                        val p = viewport.view(PointN(block.left, block.top))
                        drawRect(
                            Color(0x663F8CFF),
                            Offset(p.x * size.width, p.y * size.height),
                            Size(
                                (block.right - block.left) / viewport.width * size.width,
                                (block.bottom - block.top) / viewport.height * size.height,
                            ),
                        )
                    }
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
        selection?.let { selected ->
            Box(Modifier.fillMaxSize()) {
                ReaderSelectionHandles(selected, frame, viewport, transform, onSelectionMove)
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
