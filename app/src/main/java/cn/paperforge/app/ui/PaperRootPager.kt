package cn.paperforge.app.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Only a settled finger gesture bypasses the existing stationary route handoff. */
internal class RootMotionOrigin {
    private var target: Int? = null

    fun mark(root: Int) {
        target = root
    }

    fun consume(from: PaperDestination, to: PaperDestination): Boolean {
        val root = if (to.tab < 2) 0 else to.tab - 1
        val matches = from.screen == "home" && to.screen == "home" && target == root
        target = null
        return matches
    }
}

internal val LocalRootMotionOrigin = staticCompositionLocalOf { RootMotionOrigin() }
internal val LocalRootSwipeGate = staticCompositionLocalOf<RootSwipeGate?> { null }

@Composable
internal fun Modifier.rootSwipeChild(): Modifier =
    LocalRootSwipeGate.current?.let { rootSwipeChild(it) } ?: this

@Composable
internal fun Modifier.rootSwipeInput(): Modifier =
    LocalRootSwipeGate.current?.let { rootSwipeInput(it) } ?: this

/** One controller per HomeScreen; no document/database effects live in preloaded pages. */
@Stable
internal class RootSwipeGate {
    private var childTouches by mutableIntStateOf(0)
    private val focusedInputs = mutableStateMapOf<Any, Unit>()
    val childTouch
        get() = childTouches > 0

    val inputFocused
        get() = focusedInputs.isNotEmpty()

    var moving by mutableStateOf(false)
    var pagePosition by mutableFloatStateOf(0f)
    val iconCenters = mutableStateMapOf<Int, androidx.compose.ui.geometry.Offset>()
    var clickSerial by mutableIntStateOf(0)
    var gestureRoot: Int? = null
    var requestedRoot by mutableStateOf<Int?>(null)

    fun beginChild() {
        childTouches++
    }

    fun endChild() {
        childTouches = (childTouches - 1).coerceAtLeast(0)
    }

    fun focus(owner: Any, focused: Boolean) {
        if (focused) focusedInputs[owner] = Unit else focusedInputs.remove(owner)
    }

    fun navigate(root: Int) {
        gestureRoot = null
        requestedRoot = root
        clickSerial++
    }
}

/** Observe child input before the pager, without consuming text selection/scroll events. */
internal fun Modifier.rootSwipeChild(gate: RootSwipeGate): Modifier =
    pointerInput(gate) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gate.beginChild()
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
            } finally {
                gate.endChild()
            }
        }
    }

@Composable
internal fun Modifier.rootSwipeInput(gate: RootSwipeGate): Modifier {
    val owner = remember { Any() }
    DisposableEffect(gate, owner) { onDispose { gate.focus(owner, false) } }
    return rootSwipeChild(gate).onFocusChanged { gate.focus(owner, it.hasFocus) }
}

@Composable
internal fun PaperRootPager(
    activeRoot: Int,
    gate: RootSwipeGate,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSettled: (Int) -> Unit,
    page: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = activeRoot, pageCount = { 3 })
    var synchronizedClick by remember { mutableIntStateOf(gate.clickSerial) }
    val origin = LocalRootMotionOrigin.current
    val latestRoot by rememberUpdatedState(activeRoot)
    val latestCommit by rememberUpdatedState(onSettled)
    val density = LocalDensity.current
    val motion = LocalPaperMotion.current
    val context = LocalContext.current
    val accessibility =
        remember(context) {
            context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        }
    var exploration by
        remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener =
            android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener {
                exploration = it
            }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    val defaultFling =
        PagerDefaults.flingBehavior(
            state = pager,
            pagerSnapDistance = PagerSnapDistance.atMost(1),
            snapAnimationSpec =
                spring(dampingRatio = 1f, stiffness = 360f, visibilityThreshold = .5f),
            snapPositionalThreshold = .20f,
        )
    val fling =
        remember(defaultFling, density, gate) {
            object : TargetedFlingBehavior {
                override suspend fun ScrollScope.performFling(
                    initialVelocity: Float,
                    onRemainingDistanceUpdated: (Float) -> Unit,
                ): Float {
                    val start = gate.gestureRoot ?: latestRoot
                    val distance =
                        abs(pager.currentPage - start + pager.currentPageOffsetFraction) *
                            pager.layoutInfo.pageSize
                    val velocity =
                        if (
                            abs(initialVelocity) >= with(density) { 550.dp.toPx() } &&
                                distance >= with(density) { 32.dp.toPx() }
                        )
                            initialVelocity
                        else 0f
                    return with(defaultFling) { performFling(velocity, onRemainingDistanceUpdated) }
                }
            }
        }
    LaunchedEffect(activeRoot, gate.clickSerial, motion.reduced) {
        // Bottom navigation changes instantly inside the pager. DocumentWorkspace still owns
        // its original V6 click motion. scrollToPage also cancels an obsolete finger settle.
        gate.gestureRoot = null
        val intent = gate.clickSerial
        val target = gate.requestedRoot ?: activeRoot
        // A navigation click must outrank a still-held UserInput drag. Default-priority
        // scrollToPage alone can lose to that drag and let its obsolete fling win later.
        if (pager.isScrollInProgress) pager.scroll(MutatePriority.PreventUserInput) {}
        if (
            pager.currentPage != target ||
                pager.currentPageOffsetFraction != 0f ||
                pager.isScrollInProgress
        )
            pager.scrollToPage(target)
        // Wait for the business-state collector too; a stale composition must not acknowledge
        // the click and let an old, queued Drag.Start overwrite the newer explicit destination.
        if (gate.clickSerial == intent && activeRoot == target) {
            gate.requestedRoot = null
            synchronizedClick = intent
        }
    }
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { interaction ->
            if (
                interaction is DragInteraction.Start &&
                    gate.requestedRoot == null &&
                    synchronizedClick == gate.clickSerial
            )
                gate.gestureRoot = latestRoot
            if (interaction is DragInteraction.Cancel && gate.gestureRoot != null) {
                gate.gestureRoot = null
                launch {
                    pager.animateScrollToPage(
                        latestRoot,
                        animationSpec =
                            spring(dampingRatio = 1f, stiffness = 360f, visibilityThreshold = .5f),
                    )
                }
            }
        }
    }
    LaunchedEffect(pager) {
        // settledPage may still contain the previous page in the snapshot that first clears
        // isScrollInProgress. At rest currentPage is the actual visible snapped page.
        snapshotFlow {
                Triple(pager.isScrollInProgress, pager.currentPage, pager.currentPageOffsetFraction)
            }
            .distinctUntilChanged()
            .collect { (moving, settled, offset) ->
                gate.pagePosition = (settled + offset).coerceIn(0f, 2f)
                gate.moving = moving
                // Native drag interaction delivery and the first scrolling snapshot need not
                // arrive in the same order. Actual pager movement is also a gesture origin.
                if (
                    moving &&
                        gate.requestedRoot == null &&
                        gate.gestureRoot == null &&
                        synchronizedClick == gate.clickSerial
                )
                    gate.gestureRoot = latestRoot
                if (!moving) {
                    val started = gate.gestureRoot
                    gate.gestureRoot = null
                    if (
                        gate.requestedRoot == null &&
                            synchronizedClick == gate.clickSerial &&
                            started != null &&
                            started == latestRoot &&
                            settled != started
                    ) {
                        origin.mark(settled)
                        latestCommit(settled)
                    }
                }
            }
    }
    DisposableEffect(gate) {
        onDispose {
            gate.moving = false
            gate.gestureRoot = null
        }
    }
    HorizontalPager(
        state = pager,
        modifier = modifier.fillMaxSize().clipToBounds().testTag("root-pager"),
        beyondViewportPageCount = 1,
        key = { listOf("documents", "workspace", "settings")[it] },
        userScrollEnabled =
            enabled &&
                gate.requestedRoot == null &&
                !motion.reduced &&
                !exploration &&
                !gate.childTouch &&
                !gate.inputFocused,
        flingBehavior = fling,
        // No stretch, dimming, fake fourth page, or screenshot stand-ins at either boundary.
        overscrollEffect = null,
    ) { root ->
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("root-page-$root")
        ) {
            CompositionLocalProvider(LocalRootSwipeGate provides gate) { page(root) }
        }
    }
}
