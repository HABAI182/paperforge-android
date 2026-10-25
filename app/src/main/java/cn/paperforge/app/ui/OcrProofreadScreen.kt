package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrProofreadScreen(vm: MainViewModel) {
    val stored by vm.proofPage.collectAsState()
    val pages by vm.pages.collectAsState()
    val page = pages.firstOrNull { it.id == stored?.id } ?: stored
    val busy by vm.busy.collectAsState()
    val run by vm.proofRegion.collectAsState()
    val clipboard = LocalClipboardManager.current
    val controlsScroll = rememberScrollState()
    var regional by rememberSaveable { mutableStateOf(false) }
    var lineIndex by rememberSaveable(page?.id) { mutableIntStateOf(0) }
    var wordIndex by rememberSaveable(page?.id) { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf("") }
    var region by remember(page?.id) { mutableStateOf(OcrRegion(.1f, .1f, .9f, .3f)) }
    val layout = remember(page?.ocrLayout) { page?.ocrLayout?.let(OcrLayout::decode) }
    val line = layout?.lines?.getOrNull(lineIndex)
    LaunchedEffect(page?.ocrLayout, lineIndex, wordIndex) {
        if (layout != null && lineIndex > layout.lines.lastIndex)
            lineIndex = layout.lines.lastIndex.coerceAtLeast(0)
        draft = wordIndex?.let { line?.elements?.getOrNull(it)?.text } ?: line?.text.orEmpty()
    }
    val previewPage =
        page?.copy(
            filter = "ORIGINAL",
            brightness = 0f,
            contrast = 1f,
            ocrText = null,
            ocrLayout = null,
            processingMetadata = "{}",
            revision = 0,
            detectionConfidence = null,
            detectionMethod = null,
        )
    val preview = previewPage?.let { pagePreview(it, 1800) }
    val activeRun = run?.takeIf { it.pageId == page?.id && it.revision == page?.revision }
    val mergeable =
        remember(activeRun, layout, page?.ocrText) {
            if (activeRun == null || (layout == null && !page?.ocrText.isNullOrBlank())) false
            else
                OcrRegionMerge.apply(
                        layout ?: OcrLayout(activeRun.width, activeRun.height, emptyList()),
                        activeRun.region,
                        activeRun.result,
                    )
                    .canMerge
        }
    LaunchedEffect(activeRun) {
        if (activeRun != null) {
            withFrameNanos {}
            controlsScroll.animateScrollTo(controlsScroll.maxValue)
        } else controlsScroll.scrollTo(0)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文字校对") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "editor" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回扫描")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !regional,
                    onClick = { regional = false },
                    label = { Text("逐行与逐词") },
                )
                FilterChip(
                    selected = regional,
                    onClick = { regional = true },
                    label = { Text("框选重识别") },
                )
            }
            val canvas: @Composable () -> Unit = {
                ProofCanvas(
                    preview?.bitmap,
                    layout,
                    lineIndex,
                    wordIndex,
                    regional,
                    region,
                    onSelect = { index, word ->
                        lineIndex = index
                        wordIndex = word
                    },
                    onRegion = {
                        region = it
                        vm.proofRegion.value = null
                    },
                    error = preview?.error,
                )
            }
            val controls: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(controlsScroll).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (regional) {
                        Text("在图上拖出矩形，只识别框内内容。", style = MaterialTheme.typography.bodySmall)
                        OcrOptionsControl()
                        Button(
                            onClick = { page?.let { vm.recognizeRegion(it, region) } },
                            enabled = page != null && region.valid() && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("识别选区")
                        }
                        activeRun?.let { result ->
                            SelectionContainer {
                                Text(
                                    result.result.text.ifBlank { "选区中未识别到文字" },
                                    Modifier.heightIn(max = 150.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(result.result.text))
                                    },
                                    enabled = result.result.text.isNotBlank(),
                                ) {
                                    Text("复制选区")
                                }
                                Button(
                                    onClick = { page?.let(vm::applyRegion) },
                                    enabled = mergeable && !busy,
                                ) {
                                    Text("合并到本页")
                                }
                            }
                            if (!mergeable && result.result.text.isNotBlank())
                                Text("可扩大选区到整行，或复制后校正。", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (line != null && page != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    lineIndex--
                                    wordIndex = null
                                },
                                enabled = lineIndex > 0,
                            ) {
                                Text("上一行")
                            }
                            Text("${lineIndex+1} / ${layout?.lines?.size ?: 0}")
                            TextButton(
                                onClick = {
                                    lineIndex++
                                    wordIndex = null
                                },
                                enabled = lineIndex < (layout?.lines?.lastIndex ?: -1),
                            ) {
                                Text("下一行")
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = wordIndex == null,
                                onClick = { wordIndex = null },
                                label = { Text("整行") },
                            )
                            line.elements.forEachIndexed { index, element ->
                                FilterChip(
                                    selected = wordIndex == index,
                                    onClick = { wordIndex = index },
                                    label = { Text(element.text) },
                                )
                            }
                        }
                        OutlinedTextField(
                            draft,
                            { draft = it },
                            label = { Text(if (wordIndex == null) "当前行" else "当前词") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = { vm.correctProofLine(page, lineIndex, wordIndex, draft) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保存这一处")
                        }
                    } else {
                        Text(
                            if (page?.ocrText.isNullOrBlank()) "先识别本页文字，或直接框选一个区域。"
                            else "当前为自由文字校正，可打开完整文字继续编辑，或框选复制新识别内容。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                page?.let {
                                    if (it.ocrText.isNullOrBlank()) vm.recognize(it)
                                    else vm.openOcr(it)
                                }
                            },
                            enabled = page != null && !busy,
                        ) {
                            Text(if (page?.ocrText.isNullOrBlank()) "识别本页" else "打开完整校正")
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth >= 600.dp)
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1.2f).fillMaxHeight()) { canvas() }
                        Box(Modifier.weight(1f).fillMaxHeight()) { controls() }
                    }
                else
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { canvas() }
                        Box(
                            if (regional) Modifier.fillMaxWidth().height(320.dp)
                            else Modifier.fillMaxWidth().heightIn(max = 360.dp)
                        ) {
                            controls()
                        }
                    }
            }
        }
    }
}

@Composable
private fun ProofCanvas(
    bitmap: Bitmap?,
    layout: OcrLayout?,
    selected: Int,
    word: Int?,
    regional: Boolean,
    region: OcrRegion,
    onSelect: (Int, Int?) -> Unit,
    onRegion: (OcrRegion) -> Unit,
    error: String? = null,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var anchor by remember { mutableStateOf(PointN(.5f, .5f)) }
    val color = MaterialTheme.colorScheme.primary
    val line = layout?.lines?.getOrNull(selected)
    val polygon = word?.let { line?.elements?.getOrNull(it)?.corners } ?: line?.corners
    val center =
        if (regional) anchor
        else
            polygon?.let {
                PointN(
                    it.map { p -> p.x }.average().toFloat(),
                    it.map { p -> p.y }.average().toFloat(),
                )
            } ?: PointN(.5f, .5f)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = {
                    anchor =
                        PointN((region.left + region.right) / 2, (region.top + region.bottom) / 2)
                    zoom = if (zoom == 1f) 2.5f else 1f
                }
            ) {
                Text(if (zoom == 1f) "放大定位" else "适合页面")
            }
        }
        BoxWithConstraints(
            Modifier.weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap == null) {
                if (error != null) Text("原图暂时无法显示，可返回扫描页重拍") else CircularProgressIndicator()
            } else {
                val ratio = bitmap.width.toFloat() / bitmap.height
                val frame =
                    if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio)
                    else Modifier.fillMaxWidth().aspectRatio(ratio)
                Box(
                    frame.graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        val limit = (zoom - 1) / 2
                        translationX =
                            ((.5f - center.x) * zoom).coerceIn(-limit, limit) * size.width
                        translationY =
                            ((.5f - center.y) * zoom).coerceIn(-limit, limit) * size.height
                    }
                ) {
                    Image(bitmap.asImageBitmap(), "校对原图", Modifier.fillMaxSize())
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(regional, layout) {
                            if (regional) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val start =
                                        PointN(
                                            (down.position.x / size.width).coerceIn(0f, 1f),
                                            (down.position.y / size.height).coerceIn(0f, 1f),
                                        )
                                    onRegion(OcrRegion.between(start, start))
                                    drag(down.id) { change ->
                                        change.consume()
                                        onRegion(
                                            OcrRegion.between(
                                                start,
                                                PointN(
                                                    change.position.x / size.width,
                                                    change.position.y / size.height,
                                                ),
                                            )
                                        )
                                    }
                                }
                            } else
                                detectTapGestures { position ->
                                    val p =
                                        PointN(position.x / size.width, position.y / size.height)
                                    fun hit(points: List<PointN>) =
                                        p.x in points.minOf { it.x }..points.maxOf { it.x } &&
                                            p.y in points.minOf { it.y }..points.maxOf { it.y }
                                    val index =
                                        layout?.lines?.indexOfFirst { hit(it.corners) } ?: -1
                                    if (index >= 0)
                                        onSelect(
                                            index,
                                            layout
                                                ?.lines
                                                ?.get(index)
                                                ?.elements
                                                ?.indexOfFirst { hit(it.corners) }
                                                ?.takeIf { it >= 0 },
                                        )
                                }
                        }
                    ) {
                        fun outline(points: List<PointN>, tint: Color, width: Float) {
                            val centerX = points.map { it.x }.average().toFloat()
                            val centerY = points.map { it.y }.average().toFloat()
                            val pad = 1.5.dp.toPx() / zoom
                            val path =
                                Path().apply {
                                    points.forEachIndexed { i, p ->
                                        val x = p.x * size.width + if (p.x < centerX) -pad else pad
                                        val y = p.y * size.height + if (p.y < centerY) -pad else pad
                                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                                    }
                                    close()
                                }
                            drawPath(path, tint, style = Stroke(width))
                        }
                        layout?.lines?.forEachIndexed { index, item ->
                            outline(
                                item.corners,
                                if (!regional && index == selected) color
                                else color.copy(alpha = .25f),
                                if (index == selected) 1.1.dp.toPx() / zoom else .5.dp.toPx() / zoom,
                            )
                        }
                        if (!regional && word != null && polygon != null)
                            outline(polygon, color, 1.4.dp.toPx() / zoom)
                        if (regional) {
                            val topLeft = Offset(region.left * size.width, region.top * size.height)
                            val area =
                                androidx.compose.ui.geometry.Size(
                                    region.width * size.width,
                                    region.height * size.height,
                                )
                            drawRect(color.copy(alpha = .14f), topLeft, area)
                            drawRect(color, topLeft, area, style = Stroke(2.dp.toPx() / zoom))
                        }
                    }
                }
            }
        }
    }
}
