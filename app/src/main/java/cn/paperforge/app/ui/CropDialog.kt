package cn.paperforge.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CropDialog(
    page: PageRow,
    onDismiss: () -> Unit,
    onSave: (Quad, Float) -> Unit,
    embedded: Boolean = false,
    onDraft: (Quad, Float) -> Unit = { _, _ -> },
) {
    // The photo under the outline depends on the photo only: keyed on the draft, every drag decoded it again.
    val photo = remember(page.id, page.original) { page }
    val bitmap = pageBitmap(photo, 1400, true)
    val pointSaver = remember {
        Saver<List<PointN>, String>(
            save = { Quad(it).encode() },
            restore = { Quad.decode(it).points },
        )
    }
    var points by
        rememberSaveable(page.id, stateSaver = pointSaver) {
            mutableStateOf(Quad.decode(page.corners).points)
        }
    var aspect by rememberSaveable(page.id) { mutableFloatStateOf(page.outputAspect) }
    val touchExploration = rememberTouchExploration()
    var precise by rememberSaveable(page.id) { mutableStateOf(touchExploration) }
    LaunchedEffect(touchExploration) { if (touchExploration) precise = true }
    var corner by rememberSaveable(page.id) { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    val outlineColor = MaterialTheme.colorScheme.primary
    // A sharper copy for the loupe, loaded once the view's own preview is up.
    val detail = if (bitmap != null) pageBitmap(photo, 2400, true) else null
    // The handle being placed: 0-3 corners, 4-7 edges (top, right, bottom, left), -1 none. It stays selected after
    // the finger lifts so the nudge buttons can move it.
    var selected by remember(page.id) { mutableIntStateOf(-1) }
    // Where along the selected edge the finger took hold of it, 0..1; the loupe looks there.
    var edgeAt by remember { mutableFloatStateOf(.5f) }
    // The fingertip, in crop-view pixels, while a handle is dragged.
    var finger by remember { mutableStateOf(Offset.Unspecified) }
    var loupeWanted by remember { mutableStateOf(false) }
    var nudges by remember { mutableIntStateOf(0) }
    LaunchedEffect(nudges) {
        if (nudges > 0) {
            loupeWanted = true
            kotlinx.coroutines.delay(1500)
            if (!finger.isSpecified) loupeWanted = false
        }
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun nudge(dx: Int, dy: Int, times: Int = 1) {
        val handle = selected
        val b = bitmap ?: return
        if (handle < 0) return
        // 0.1% of the photo's long side per step, the same distance across and down.
        val long = maxOf(b.width, b.height) / 1000f
        val sx = dx * times * long / b.width
        val sy = dy * times * long / b.height
        points =
            if (handle >= 4) CropGeometry.moveEdge(Quad(points), handle - 4, sx, sy).points
            else {
                val p = points[handle]
                val candidate = points.toMutableList()
                candidate[handle] = PointN((p.x + sx).coerceIn(0f, 1f), (p.y + sy).coerceIn(0f, 1f))
                if (Quad(candidate).valid()) candidate else points
            }
        nudges++
    }
    var detecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(points, aspect) { if (embedded) onDraft(Quad(points), aspect) }
    ScanToolWindow(embedded, onDismiss) {
        Surface(
            Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val canvasHeight = (maxHeight - 270.dp).coerceIn(180.dp, 700.dp)
                Column(
                    Modifier.fillMaxSize()
                        .then(if (embedded) Modifier else Modifier.systemBarsPadding())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!embedded)
                            Text("沿纸边拖动四角或整边", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "拖动圆点或边线中点，手指可放在圆点旁边；放大镜会避开手指。松手后可用方向键逐步微调。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Box(
                            Modifier.height(canvasHeight).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            bitmap?.let { b ->
                                BoxWithConstraints(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val ratio = b.width.toFloat() / b.height
                                    val imageModifier =
                                        if (maxWidth / maxHeight > ratio)
                                            Modifier.fillMaxHeight().aspectRatio(ratio)
                                        else Modifier.fillMaxWidth().aspectRatio(ratio)
                                    val density = androidx.compose.ui.platform.LocalDensity.current
                                    val viewW = constraints.maxWidth.toFloat()
                                    val viewH = constraints.maxHeight.toFloat()
                                    val shown =
                                        if (viewW / viewH > ratio) Size(viewH * ratio, viewH)
                                        else Size(viewW, viewW / ratio)
                                    val origin = Offset((viewW - shown.width) / 2, (viewH - shown.height) / 2)
                                    Box(imageModifier) {
                                        Image(
                                            b.asImageBitmap(),
                                            "裁切原图",
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        Canvas(
                                            Modifier.fillMaxSize()
                                                .testTag("crop-canvas")
                                                .semantics {
                                                    contentDescription = "裁切区域"
                                                    stateDescription = "已选择四角，可用位置调整代替拖动"
                                                    onClick("调整四角位置") {
                                                        precise = true
                                                        true
                                                    }
                                                    customActions =
                                                        listOf(
                                                            CustomAccessibilityAction("调整四角位置") {
                                                                precise = true
                                                                true
                                                            },
                                                            CustomAccessibilityAction("使用整张图片") {
                                                                points = Quad.Full.points
                                                                true
                                                            },
                                                        )
                                                }
                                                .pointerInput(b) {
                                                    awaitEachGesture {
                                                        val down = awaitFirstDown(requireUnconsumed = false)
                                                        val w = size.width.toFloat()
                                                        val h = size.height.toFloat()
                                                        val handle =
                                                            CropGeometry.hit(
                                                                Quad(points),
                                                                w,
                                                                h,
                                                                down.position.x,
                                                                down.position.y,
                                                                36.dp.toPx(),
                                                                24.dp.toPx(),
                                                            )
                                                        if (handle < 0) {
                                                            selected = -1
                                                            return@awaitEachGesture
                                                        }
                                                        down.consume()
                                                        val start = points
                                                        if (handle >= 4) {
                                                            val a = start[handle - 4]
                                                            val c = start[(handle - 3) % 4]
                                                            val dx = (c.x - a.x) * w
                                                            val dy = (c.y - a.y) * h
                                                            edgeAt =
                                                                (((down.position.x - a.x * w) * dx +
                                                                        (down.position.y - a.y * h) * dy) /
                                                                        (dx * dx + dy * dy).coerceAtLeast(1f))
                                                                    .coerceIn(.08f, .92f)
                                                        }
                                                        selected = handle
                                                        finger = down.position
                                                        loupeWanted = true
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        var last = down.position
                                                        try {
                                                            do {
                                                                val event = awaitPointerEvent()
                                                                val change =
                                                                    event.changes.firstOrNull { it.id == down.id }
                                                                        ?: break
                                                                change.consume()
                                                                if (handle >= 4) {
                                                                    val amount = change.position - last
                                                                    points =
                                                                        CropGeometry.moveEdge(
                                                                                Quad(points),
                                                                                handle - 4,
                                                                                amount.x / w,
                                                                                amount.y / h,
                                                                            )
                                                                            .points
                                                                } else {
                                                                    // Moves with the finger from where it went down, so
                                                                    // the corner does not jump under the fingertip: a
                                                                    // finger held beside the corner leaves it in view.
                                                                    val moved = change.position - down.position
                                                                    val p = start[handle]
                                                                    val candidate = points.toMutableList()
                                                                    candidate[handle] =
                                                                        PointN(
                                                                            (p.x + moved.x / w).coerceIn(0f, 1f),
                                                                            (p.y + moved.y / h).coerceIn(0f, 1f),
                                                                        )
                                                                    if (Quad(candidate).valid()) points = candidate
                                                                }
                                                                finger = change.position
                                                                last = change.position
                                                            } while (event.changes.any { it.pressed })
                                                        } finally {
                                                            finger = Offset.Unspecified
                                                            loupeWanted = false
                                                        }
                                                    }
                                                }
                                        ) {
                                            val path =
                                                Path().apply {
                                                    points.forEachIndexed { i, p ->
                                                        if (i == 0) moveTo(p.x * size.width, p.y * size.height)
                                                        else lineTo(p.x * size.width, p.y * size.height)
                                                    }
                                                    close()
                                                }
                                            drawPath(path, outlineColor.copy(alpha = .15f))
                                            drawPath(path, Color.White, style = Stroke(5.dp.toPx()))
                                            drawPath(path, outlineColor, style = Stroke(2.5.dp.toPx()))
                                            points.indices.forEach { i ->
                                                val a = Offset(points[i].x * size.width, points[i].y * size.height)
                                                val c =
                                                    Offset(
                                                        points[(i + 1) % 4].x * size.width,
                                                        points[(i + 1) % 4].y * size.height,
                                                    )
                                                if (selected == i + 4) {
                                                    drawLine(Color.White, a, c, 7.dp.toPx())
                                                    drawLine(outlineColor, a, c, 4.dp.toPx())
                                                }
                                                val middle = (a + c) / 2f
                                                drawCircle(Color.White, 9.dp.toPx(), middle)
                                                drawCircle(outlineColor, 5.dp.toPx(), middle)
                                            }
                                            points.forEachIndexed { i, p ->
                                                val at = Offset(p.x * size.width, p.y * size.height)
                                                if (i == selected) {
                                                    // A ring with a centre dot: the exact point stays visible.
                                                    drawCircle(Color.White, 13.dp.toPx(), at, style = Stroke(4.dp.toPx()))
                                                    drawCircle(outlineColor, 13.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                                                    drawCircle(Color.White, 2.5.dp.toPx(), at)
                                                    drawCircle(outlineColor, 1.5.dp.toPx(), at)
                                                } else {
                                                    drawCircle(Color.White, 10.dp.toPx(), at)
                                                    drawCircle(outlineColor, 5.dp.toPx(), at)
                                                }
                                            }
                                        }
                                    }
                                    // The loupe, over the whole view so it can sit beside the photo when there is room.
                                    val target = handleTarget(points, selected, edgeAt)
                                    val loupeOn = loupeWanted && target != null
                                    val loupeAlpha by
                                        animateFloatAsState(
                                            if (loupeOn) 1f else 0f,
                                            tween(if (loupeOn) 90 else 200),
                                            label = "loupe",
                                        )
                                    val radius = with(density) { 56.dp.toPx() }.coerceAtMost(minOf(viewW, viewH) * .26f)
                                    val placed = remember { arrayOfNulls<PointN>(1) }
                                    val spot =
                                        if (loupeOn)
                                            LoupePlacement.place(
                                                    viewW,
                                                    viewH,
                                                    PointN(
                                                        origin.x + target.x * shown.width,
                                                        origin.y + target.y * shown.height,
                                                    ),
                                                    if (finger.isSpecified)
                                                        PointN(origin.x + finger.x, origin.y + finger.y)
                                                    else null,
                                                    radius,
                                                    with(density) { 40.dp.toPx() },
                                                    with(density) { 6.dp.toPx() },
                                                    placed[0],
                                                )
                                                .also { placed[0] = it }
                                        else {
                                            placed[0] = null
                                            null
                                        }
                                    val loupeCenter = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
                                    LaunchedEffect(spot) {
                                        val to = spot?.let { Offset(it.x, it.y) } ?: return@LaunchedEffect
                                        if (loupeAlpha < .05f) loupeCenter.snapTo(to)
                                        else
                                            loupeCenter.animateTo(
                                                to,
                                                spring(dampingRatio = .85f, stiffness = Spring.StiffnessMediumLow),
                                            )
                                    }
                                    val shownTarget = remember { arrayOfNulls<PointN>(1) }
                                    if (target != null) shownTarget[0] = target
                                    val lens = remember(detail, b) { (detail ?: b).asImageBitmap() }
                                    Canvas(
                                        Modifier.matchParentSize()
                                            .clearAndSetSemantics {
                                                testTag = "crop-loupe"
                                                if (spot != null) this[LoupeSpot] = Offset(spot.x, spot.y)
                                            }
                                            .graphicsLayer { alpha = loupeAlpha }
                                    ) {
                                        // Every animated value is read before any early return: a draw that read
                                        // none of them was never redrawn when the loupe faded in.
                                        val visible = loupeAlpha
                                        val at = loupeCenter.value
                                        val t = target ?: shownTarget[0]
                                        if (visible <= 0f || t == null) return@Canvas
                                        drawCropLoupe(
                                            lens,
                                            at,
                                            radius,
                                            3f,
                                            shown,
                                            t,
                                            points,
                                            outlineColor,
                                        )
                                    }
                                }
                            } ?: CircularProgressIndicator()
                        }
                        if (selected >= 0) {
                            val label = listOf("左上角", "右上角", "右下角", "左下角", "上边", "右边", "下边", "左边")[selected]
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "微调$label",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                NudgeKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "${label}左移", "crop-nudge-left") {
                                    nudge(-1, 0, it)
                                }
                                NudgeKey(Icons.Filled.KeyboardArrowUp, "${label}上移", "crop-nudge-up") {
                                    nudge(0, -1, it)
                                }
                                NudgeKey(Icons.Filled.KeyboardArrowDown, "${label}下移", "crop-nudge-down") {
                                    nudge(0, 1, it)
                                }
                                NudgeKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "${label}右移", "crop-nudge-right") {
                                    nudge(1, 0, it)
                                }
                                TextButton(onClick = { selected = -1 }) { Text("完成") }
                            }
                        }
                        // Outline tools first, then the output shape; both rows wrap instead of scrolling, so
                        // nothing sits off the edge of the screen.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { points = CropGeometry.expand(Quad(points), .025f).points }
                            ) {
                                Text("外扩")
                            }
                            OutlinedButton(
                                onClick = { points = CropGeometry.expand(Quad(points), -.025f).points }
                            ) {
                                Text("内缩")
                            }
                            OutlinedButton(
                                onClick = {
                                    points = Quad.decode(page.corners).points
                                    aspect = page.outputAspect
                                }
                            ) {
                                Text("重置")
                            }
                        }
                        val shapes =
                            listOf(
                                "原比例" to 0f,
                                "A4/A5" to .7071068f,
                                "方形" to 1f,
                                "证件" to (85.6f / 54f),
                            )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            shapes.forEach { (label, ratio) ->
                                FilterChip(
                                    aspect == ratio,
                                    { aspect = ratio },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text(
                            shapes.firstOrNull { it.second == aspect && it.second > 0f }?.let {
                                "保存后页面按“${it.first}”比例输出；裁剪框按纸边画，不必改成这个比例。"
                            } ?: "保存后页面保持裁剪出的原比例。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { precise = !precise }) {
                            Text(if (precise) "收起位置调整" else "按位置调整")
                        }
                        if (precise) {
                            val names = listOf("左上角", "右上角", "右下角", "左下角")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                names.forEachIndexed { index, label ->
                                    FilterChip(
                                        corner == index,
                                        { corner = index },
                                        label = { Text(label) },
                                        modifier = Modifier.testTag("crop-corner-$index"),
                                    )
                                }
                            }
                            fun move(x: Float, y: Float) {
                                val candidate =
                                    points.toMutableList().also { it[corner] = PointN(x, y) }
                                if (Quad(candidate).valid()) {
                                    points = candidate
                                    notice = ""
                                } else notice = "角点不能交叉，请先调整相邻角点"
                            }
                            val selected = points[corner]
                            Text(
                                "${names[corner]} · 水平 ${(selected.x*100).toInt()}%，垂直 ${(selected.y*100).toInt()}%",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            Slider(
                                selected.x,
                                { move(it, selected.y) },
                                steps = 99,
                                modifier =
                                    Modifier.testTag("crop-x").semantics {
                                        contentDescription = "${names[corner]}水平位置"
                                    },
                            )
                            Slider(
                                selected.y,
                                { move(selected.x, it) },
                                steps = 99,
                                modifier =
                                    Modifier.testTag("crop-y").semantics {
                                        contentDescription = "${names[corner]}垂直位置"
                                    },
                            )
                            if (notice.isNotBlank())
                                Text(
                                    notice,
                                    modifier =
                                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    color = MaterialTheme.colorScheme.error,
                                )
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = { points = Quad.Full.points }) { Text("全图") }
                        TextButton(
                            onClick = {
                                points = CropGeometry.expand(Quad(points), .02f).points
                                notice = "已向外留边，避免裁掉页边文字。"
                            }
                        ) {
                            Text("向外留边")
                        }
                        TextButton(
                            enabled = bitmap != null && !detecting,
                            onClick = {
                                detecting = true
                                scope.launch {
                                    try {
                                        val result =
                                            withContext(Dispatchers.Default) {
                                                ScanProcessor.detectFile(
                                                    java.io.File(page.original),
                                                    lockReference = false,
                                                )
                                            }
                                        result.quad?.let { points = it.points }
                                        notice =
                                            if (result.quad == null) "未找到完整纸边，已保留当前四角；可手动微调。"
                                            else if (result.confidence < .6f) "已从原图重检，边缘较弱，请检查四角。"
                                            else "已从原始照片重新检测纸边。"
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        notice = failure.message ?: "重新检测失败，当前四角已保留。"
                                    } finally {
                                        detecting = false
                                    }
                                }
                            },
                        ) {
                            Text(if (detecting) "检测中…" else "重新检测")
                        }
                        if (!embedded) TextButton(onClick = onDismiss) { Text("取消") }
                        if (!embedded)
                            Button(
                                onClick = { onSave(Quad(points), aspect) },
                                enabled = Quad(points).valid(),
                            ) {
                                Text("应用")
                            }
                    }
                }
            }
        }
    }
}

/** Where the loupe is placed in the crop view, in view pixels, for tests. */
val LoupeSpot = SemanticsPropertyKey<Offset>("LoupeSpot")

/** The point the loupe looks at: a corner, or the place along an edge where the finger took hold of it. */
private fun handleTarget(points: List<PointN>, handle: Int, along: Float): PointN? =
    when (handle) {
        in 0..3 -> points[handle]
        in 4..7 -> {
            val a = points[handle - 4]
            val b = points[(handle - 3) % 4]
            PointN(a.x + (b.x - a.x) * along, a.y + (b.y - a.y) * along)
        }
        else -> null
    }

/** One step per tap; held, it repeats after 0.4 s and speeds up after ten steps. */
@Composable
private fun NudgeKey(icon: ImageVector, description: String, tag: String, onStep: (Int) -> Unit) {
    val step by rememberUpdatedState(onStep)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier =
            Modifier.size(44.dp)
                .testTag(tag)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    onClick {
                        step(1)
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            coroutineScope {
                                // Undispatched: the first step lands even when the tap is released at once.
                                val repeat = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                    step(1)
                                    delay(400)
                                    var count = 0
                                    while (true) {
                                        step(if (count < 10) 1 else 3)
                                        count++
                                        delay(60)
                                    }
                                }
                                tryAwaitRelease()
                                repeat.cancel()
                            }
                        }
                    )
                },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
    }
}
