package cn.paperforge.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.PageRow
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Equal slots keep shutter, centred mode rail and batch switch on the same axis. */
@Composable
internal fun CaptureActionBar(
    landscape: Boolean,
    left: @Composable () -> Unit,
    shutter: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    if (landscape)
        Column(
            Modifier.width(108.dp).fillMaxHeight().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { left() }
            Box(
                Modifier.weight(1f).fillMaxWidth().testTag("capture-shutter-slot"),
                contentAlignment = Alignment.Center,
            ) {
                shutter()
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { right() }
        }
    else
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("capture-action-bar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { left() }
            Box(
                Modifier.weight(1f).testTag("capture-shutter-slot"),
                contentAlignment = Alignment.Center,
            ) {
                shutter()
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { right() }
        }
}

@Composable
internal fun CaptureRecentPage(
    page: PageRow,
    count: Int,
    enabled: Boolean,
    onPreview: () -> Unit,
    onModify: () -> Unit,
    finishing: Boolean = false,
) {
    // While the page is still being finished, its look is not settled: the thumbnail shows the plain photo
    // cut along the page's outline, with a spinner, instead of a half-made page.
    val preview = pagePreview(if (finishing) page.copy(filter = "ORIGINAL") else page, 180)
    Column(
        Modifier.width(76.dp).testTag("capture-recent-page"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BadgedBox(badge = { Badge { Text("$count") } }) {
            Surface(
                onClick = onPreview,
                enabled = enabled,
                shape = RoundedCornerShape(8.dp),
                color = Color.White,
            ) {
                Box(Modifier.size(64.dp, 78.dp), contentAlignment = Alignment.Center) {
                    preview.bitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            "预览第 $count 页",
                            Modifier.fillMaxSize().padding(2.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    if (preview.loading || finishing)
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else if (preview.error != null) Icon(Icons.Default.Description, "页面预览")
                }
            }
        }
        TextButton(
            onClick = onModify,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) {
            Text("修改", color = Color(0xFF86E5B8), maxLines = 1)
        }
    }
}

/**
 * Swipeable single-sheet page review.
 *
 * The sheet shows the document's real page order (not just the last capture): swipe left/right,
 * the title tracks the visible page, and every action is bound to the page currently on screen.
 * Swiping past the first/last page does not silently do nothing: it shows a short boundary hint and
 * a haptic tick, so the owner can tell an edge from an ignored gesture.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun CapturePagePreview(
    pages: List<PageRow>,
    startNumber: Int = 1,
    initialPage: Int = 0,
    onDismiss: () -> Unit,
    onModify: (PageRow) -> Unit,
    onRetake: (PageRow) -> Unit,
    onUndo: (PageRow) -> Unit,
    onHd: (PageRow) -> Unit,
) {
    if (pages.isEmpty()) return
    val pager =
        rememberPagerState(
            initialPage = initialPage.coerceIn(0, pages.lastIndex),
            pageCount = { pages.size },
        )
    val haptics = LocalHapticFeedback.current
    var edgeHint by remember { mutableStateOf<String?>(null) }
    val reportEdge: (String) -> Unit = { text ->
        if (edgeHint != text) {
            edgeHint = text
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    LaunchedEffect(edgeHint) {
        if (edgeHint != null) {
            delay(2200)
            edgeHint = null
        }
    }
    LaunchedEffect(pages.size) {
        if (pages.isEmpty()) onDismiss()
        else if (pager.currentPage > pages.lastIndex) pager.scrollToPage(pages.lastIndex)
    }
    val current = pages.getOrNull(pager.currentPage) ?: pages.first()
    val number = startNumber + pager.currentPage
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "第 $number 页",
                    Modifier.weight(1f).testTag("capture-preview-page-indicator"),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (pages.size > 1)
                    Text(
                        "共 ${pages.size} 页 · 左右滑动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                TextButton(onClick = { onHd(current) }) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(4.dp))
                    Text("AI 高清")
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(
                    state = pager,
                    modifier =
                        Modifier.fillMaxSize()
                            .testTag("capture-preview-pager")
                            .pointerInput(pages.size) {
                                awaitEachGesture {
                                    val startPage = pager.currentPage
                                    awaitFirstDown(requireUnconsumed = false)
                                    var travel = 0f
                                    var reported = false
                                    while (true) {
                                        val event =
                                            awaitPointerEvent(PointerEventPass.Initial)
                                        val change =
                                            event.changes.firstOrNull { it.pressed } ?: break
                                        travel += change.positionChange().x
                                        if (
                                            !reported &&
                                                abs(travel) > viewConfiguration.touchSlop * 3
                                        ) {
                                            reported = true
                                            if (startPage == 0 && travel > 0) reportEdge("已是第一页")
                                            else if (
                                                startPage == pages.lastIndex && travel < 0
                                            )
                                                reportEdge("已是最后一页")
                                        }
                                    }
                                }
                            },
                    key = { pages.getOrNull(it)?.id ?: "pending-$it" },
                    beyondViewportPageCount = 1,
                    userScrollEnabled = true,
                ) { index ->
                    pages.getOrNull(index)?.let { page ->
                        ScanPageCanvas(page, false, {}, {})
                    }
                }
                edgeHint?.let { hint ->
                    Surface(
                        modifier =
                            Modifier.align(Alignment.Center)
                                .testTag("capture-preview-edge")
                                .padding(16.dp),
                        color = Color.Black.copy(alpha = .66f),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            hint,
                            Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onUndo(current) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    Text("撤回", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { onRetake(current) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    Text("重拍", maxLines = 1)
                }
                Button(
                    onClick = { onModify(current) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    Text("修改", maxLines = 1)
                }
            }
        }
    }
}

/**
 * Backwards-compatible single-page entry. Existing callers and tests keep the original signature;
 * the implementation is the same pager, so there is only one preview behaviour to maintain.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun CaptureSinglePagePreview(
    page: PageRow,
    number: Int,
    onDismiss: () -> Unit,
    onModify: () -> Unit,
    onRetake: () -> Unit,
    onUndo: () -> Unit,
    onHd: () -> Unit,
) {
    CapturePagePreview(
        pages = listOf(page),
        startNumber = number,
        initialPage = 0,
        onDismiss = onDismiss,
        onModify = { onModify() },
        onRetake = { onRetake() },
        onUndo = { onUndo() },
        onHd = { onHd() },
    )
}
