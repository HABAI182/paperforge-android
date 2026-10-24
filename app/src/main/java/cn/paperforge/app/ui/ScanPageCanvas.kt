package cn.paperforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.PointN
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shares the reader gesture contract: at fit size swipe pages, when enlarged pan the page. */
@Composable
fun ScanPageCanvas(
    page: PageRow,
    compare: Boolean,
    onTap: () -> Unit,
    onZoom: (Boolean) -> Unit,
    highlights: List<List<PointN>> = emptyList(),
) {
    var zoom by remember(page.id) { mutableFloatStateOf(1f) }
    var pan by remember(page.id) { mutableStateOf(Offset.Zero) }
    val bitmap =
        pageBitmap(
            if (compare) page.copy(filter = "ORIGINAL", brightness = 0f, contrast = 1f) else page,
            if (zoom > 1.5f) 2800 else 1800,
        )
    val scope = rememberCoroutineScope()
    var lastTap by remember { mutableLongStateOf(0L) }
    var tapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val tap by rememberUpdatedState(onTap)
    val changeZoom by rememberUpdatedState(onZoom)
    LaunchedEffect(zoom) { changeZoom(zoom > 1.01f) }
    DisposableEffect(page.id) { onDispose { tapJob?.cancel() } }
    Box(
        Modifier.fillMaxSize()
            .clipToBounds()
            .testTag("scan-page-${page.id}")
            .semantics { stateDescription = "缩放 ${(zoom * 100).toInt()}%" }
            .pointerInput(page.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var eventTime = down.uptimeMillis
                    var multiple = false
                    var travel = Offset.Zero
                    var moved = false
                    do {
                        val event = awaitPointerEvent()
                        eventTime = event.changes.maxOf { it.uptimeMillis }
                        val fingers = event.changes.count { it.pressed }
                        multiple = multiple || fingers > 1
                        val delta = event.calculatePan()
                        travel += delta
                        if (abs(travel.x) + abs(travel.y) > viewConfiguration.touchSlop)
                            moved = true
                        if (fingers > 1 || zoom > 1.01f) {
                            val nextZoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            val factor = nextZoom / zoom
                            val anchor = event.calculateCentroid(useCurrent = false)
                            val anchorX =
                                if (anchor.x.isFinite()) anchor.x / size.width - .5f else 0f
                            val anchorY =
                                if (anchor.y.isFinite()) anchor.y / size.height - .5f else 0f
                            zoom = nextZoom
                            val bound = (zoom - 1f) / 2f
                            pan =
                                Offset(
                                    (pan.x * factor + anchorX * (1 - factor) + delta.x / size.width)
                                        .coerceIn(-bound, bound),
                                    (pan.y * factor +
                                            anchorY * (1 - factor) +
                                            delta.y / size.height)
                                        .coerceIn(-bound, bound),
                                )
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!moved && !multiple) {
                        val now = eventTime
                        if (now - lastTap < 280) {
                            tapJob?.cancel()
                            val reset = zoom > 1.01f
                            zoom = if (reset) 1f else 2f
                            pan =
                                if (reset) Offset.Zero
                                else
                                    Offset(
                                        (.5f - down.position.x / size.width).coerceIn(-.5f, .5f),
                                        (.5f - down.position.y / size.height).coerceIn(-.5f, .5f),
                                    )
                            lastTap = 0L
                        } else {
                            lastTap = now
                            tapJob?.cancel()
                            tapJob =
                                scope.launch {
                                    delay(280)
                                    tap()
                                }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x * size.width
                    translationY = pan.y * size.height
                }
            ) {
                Image(
                    it.asImageBitmap(),
                    if (compare) "原图对照" else "扫描预览",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                val bitmap = it
                Canvas(Modifier.fillMaxSize()) {
                    val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    val left = (size.width - width) / 2
                    val top = (size.height - height) / 2
                    highlights.forEach { polygon ->
                        val path =
                            Path().apply {
                                polygon.forEachIndexed { i, p ->
                                    if (i == 0) moveTo(left + p.x * width, top + p.y * height)
                                    else lineTo(left + p.x * width, top + p.y * height)
                                }
                                close()
                            }
                        drawPath(path, Color(0x66FFD54F))
                    }
                }
            }
        } ?: CircularProgressIndicator()
    }
}

@Composable
fun FocusSystemBars(fullscreen: Boolean) {
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) bars?.hide(WindowInsetsCompat.Type.systemBars())
        else bars?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
