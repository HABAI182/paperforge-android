package cn.paperforge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal val LocalNavigationExpansion = staticCompositionLocalOf<NavigationExpansion?> { null }

/** One real destination tree, revealed from the selected card over a frozen outgoing frame. */
internal class NavigationExpansion(private val scope: CoroutineScope) {
    private data class Entry(val from: Any?, val to: Any?, val bounds: Rect)

    private data class Pending(val from: Any?, val bounds: Rect)

    var capture: (suspend () -> ImageBitmap?)? = null
    var reduced = false
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    var source by mutableStateOf(Rect.Zero)
        private set

    var exiting by mutableStateOf(false)
        private set

    var currentKey by mutableStateOf<Any?>(null)
        private set

    private val entries = mutableListOf<Entry>()
    private var pending: Pending? = null
    val progress = Animatable(1f)
    private var job: Job? = null
    private var warm: Deferred<ImageBitmap?>? = null
    private var warmKey: Any? = null
    private var warmAt = 0L

    fun prewarm() {
        warm?.cancel()
        warmKey = currentKey
        warmAt = android.os.SystemClock.elapsedRealtime()
        warm = scope.async { runCatching { capture?.invoke() }.getOrNull() }
    }

    fun discardWarm() {
        warm?.cancel()
        warm = null
    }

    val active
        get() = frame != null

    fun observeRoute(key: Any?) {
        if (currentKey == key) return
        currentKey = key
        val start = pending
        if (start != null && start.from != key) {
            entries += Entry(start.from, key, start.bounds)
            pending = null
        }
    }

    private suspend fun snapshot(): ImageBitmap? {
        val prefetched =
            warm?.takeIf {
                warmKey == currentKey && android.os.SystemClock.elapsedRealtime() - warmAt < 500
            }
        warm = null
        val value =
            withTimeoutOrNull(150) {
                runCatching { if (prefetched != null) prefetched.await() else capture?.invoke() }
                    .getOrNull()
            }
        currentCoroutineContext().ensureActive()
        return value
    }

    fun open(bounds: Rect, action: () -> Unit) {
        if (reduced || bounds.width <= 0 || bounds.height <= 0 || capture == null) {
            action()
            return
        }
        job?.cancel()
        job =
            scope.launch {
                val previous = snapshot()
                if (previous == null) {
                    frame = null
                    action()
                    return@launch
                }
                source = bounds
                exiting = false
                progress.snapTo(0f)
                frame = previous
                val before = currentKey
                pending = Pending(before, bounds)
                action()
                // Start only after the destination exists and has a recorded frame. Do not spend
                // the first animation frames rendering the old route or reading GPU pixels.
                withTimeoutOrNull(500) { snapshotFlow { currentKey }.first { it != before } }
                withFrameNanos {}
                withFrameNanos {}
                try {
                    progress.animateTo(
                        1f,
                        tween(460, easing = CubicBezierEasing(.2f, 0f, .12f, 1f)),
                    )
                } finally {
                    if (currentCoroutineContext().isActive) {
                        frame = null
                        pending = null
                    }
                }
            }
    }

    fun close(action: () -> Unit) {
        val entry = entries.lastOrNull()?.takeIf { it.to == currentKey }
        if (reduced || entry == null || capture == null) {
            action()
            return
        }
        val start = if (active && !exiting) progress.value else 1f
        job?.cancel()
        job =
            scope.launch {
                val closing = snapshot()
                if (closing == null) {
                    frame = null
                    action()
                    return@launch
                }
                val before = currentKey
                source = entry.bounds
                exiting = true
                progress.snapTo(start)
                frame = closing
                pending = null
                action()
                val target =
                    withTimeoutOrNull(180) { snapshotFlow { currentKey }.first { it != before } }
                if (target != entry.from) {
                    frame = null
                    exiting = false
                    entries.clear()
                    return@launch
                }
                entries.removeAt(entries.lastIndex)
                withFrameNanos {}
                withFrameNanos {}
                try {
                    progress.animateTo(
                        0f,
                        tween(
                            (420 * start).toInt().coerceIn(150, 420),
                            easing = CubicBezierEasing(.2f, 0f, .12f, 1f),
                        ),
                    )
                } finally {
                    if (currentCoroutineContext().isActive) {
                        frame = null
                        exiting = false
                    }
                }
            }
    }

    fun finish() {
        discardWarm()
        job?.cancel()
        frame = null
        pending = null
    }
}

internal class ExpansionAction(val modifier: Modifier, val onClick: () -> Unit)

@Composable
internal fun expansionAction(action: () -> Unit): ExpansionAction {
    val expansion = LocalNavigationExpansion.current
    val latest by rememberUpdatedState(action)
    val bounds = remember { mutableStateOf(Rect.Zero) }
    return remember(expansion) {
        ExpansionAction(Modifier.onGloballyPositioned { bounds.value = it.boundsInWindow() }) {
            expansion?.open(bounds.value) { latest() } ?: latest()
        }
    }
}

@Composable
internal fun returnAction(action: () -> Unit): () -> Unit {
    val expansion = LocalNavigationExpansion.current
    val latest by rememberUpdatedState(action)
    return remember(expansion) { { expansion?.close { latest() } ?: latest() } }
}

@Composable
internal fun NavigationExpansionHost(
    controller: NavigationExpansion,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val overlay = rememberGraphicsLayer()
    val pageLayer = rememberGraphicsLayer()
    val recorded = remember { booleanArrayOf(false) }
    val motion = LocalPaperMotion.current
    val background = androidx.compose.material3.MaterialTheme.colorScheme.background
    var origin by remember { mutableStateOf(Offset.Zero) }
    SideEffect {
        controller.reduced = motion.reduced
        controller.capture = { if (recorded[0]) layer.toImageBitmap() else null }
    }
    // Prewarm at touch-down, before the ripple/click. An idle frame cache can become stale when
    // a child scrolls/updates without re-recording its parent, so do not reuse idle screenshots.
    LaunchedEffect(motion.reduced) { if (motion.reduced) controller.finish() }
    DisposableEffect(controller) {
        onDispose {
            controller.finish()
            controller.capture = null
        }
    }
    CompositionLocalProvider(LocalNavigationExpansion provides controller) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(controller) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!controller.reduced) controller.prewarm()
                        if (waitForUpOrCancellation(PointerEventPass.Initial) == null)
                            controller.discardWarm()
                    }
                }
                .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    recorded[0] = true
                    val previous = controller.frame
                    if (previous == null) drawLayer(layer)
                    else {
                        if (controller.exiting) drawLayer(layer)
                        else
                            drawImage(
                                previous,
                                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            )
                        val p = controller.progress.value
                        val start = controller.source.translate(-origin)
                        val rect = lerp(start, Rect(Offset.Zero, size), p)
                        val radius = 22.dp.toPx() * (1 - p)
                        val path =
                            Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
                        pageLayer.record {
                            // Fit the destination width into the growing container. A clip over
                            // an
                            // almost full-size page would show an empty hole until its header
                            // enters.
                            val zoom = (rect.width / size.width).coerceAtLeast(.01f)
                            translate(rect.left, rect.top) {
                                scale(zoom, zoom, pivot = Offset.Zero) {
                                    if (controller.exiting)
                                        drawImage(
                                            previous,
                                            dstSize =
                                                IntSize(size.width.toInt(), size.height.toInt()),
                                        )
                                    else drawLayer(layer)
                                }
                            }
                        }
                        // Fade through the container: never superimpose two sets of text. At the
                        // small end reveal the actual stationary source card continuously.
                        val surface = (p / .06f).coerceIn(0f, 1f)
                        val page = ((p - .06f) / .10f).coerceIn(0f, 1f)
                        pageLayer.alpha = page * page * (3f - 2f * page)
                        overlay.record {
                            clipPath(path) {
                                drawRect(
                                    background,
                                    alpha = surface * surface * (3f - 2f * surface),
                                )
                                drawLayer(pageLayer)
                            }
                        }
                        drawLayer(overlay)
                    }
                }
        ) {
            content()
        }
    }
}
