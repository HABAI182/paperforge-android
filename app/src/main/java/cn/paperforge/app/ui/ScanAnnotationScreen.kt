package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cn.paperforge.app.annotation.PageAnnotations
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.AnnotationEditor
import cn.paperforge.app.model.PageAnnotation
import cn.paperforge.app.model.PointN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val annotationTools =
    listOf(
        "pen" to "画笔",
        "highlight" to "高亮",
        "mosaic" to "打码",
        "text" to "文字",
        "eraser" to "橡皮",
    )

/**
 * Dedicated annotation mode for one scan page.
 *
 * This screen is the whole "annotation mode": the editor itself gains no permanent buttons — the
 * 更多 sheet opens this screen, the bottom tool row only exists while it is open, and leaving it
 * returns to the unchanged editor. Saving hands the annotation list to the caller, which bakes it
 * into a new page revision (see [PageAnnotations.bake]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanAnnotationScreen(
    page: PageRow,
    onBack: () -> Unit,
    onSave: (List<PageAnnotation>) -> Unit,
) {
    BackHandler { onBack() }
    var bitmap by remember(page.id, page.revision) { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(page.id, page.revision) {
        var generated: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { generated = ScanProcessor.render(page, 1600) }
            bitmap = generated
            generated = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法打开这一页"
        } finally {
            generated?.recycle()
        }
    }
    val preview = bitmap
    DisposableEffect(preview) { onDispose { preview?.recycle() } }

    val editor = remember(page.id) { AnnotationEditor() }
    var tool by remember { mutableStateOf("pen") }
    var mosaicStyle by remember { mutableStateOf("mosaic") }
    var color by remember { mutableIntStateOf(PageAnnotations.colors.first()) }
    var width by remember { mutableFloatStateOf(.006f) }
    var current by remember { mutableStateOf<PageAnnotation?>(null) }
    val erasing = remember { mutableStateListOf<String>() }
    var textAt by remember { mutableStateOf<PointN?>(null) }
    var editingText by remember { mutableStateOf<PageAnnotation?>(null) }
    var textValue by remember { mutableStateOf("") }

    // Switching tools keeps the slider meaningful: each tool has its own useful weight range.
    LaunchedEffect(tool) { width = defaultWidth(tool) }

    val annotations = rememberUpdatedState(editor.annotations)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标注 · 第 ${page.position + 1} 页") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回，不保存标注")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { editor.undo() },
                        enabled = editor.canUndo,
                        modifier = Modifier.testTag("annotate-undo").semantics {
                            contentDescription = "撤销标注"
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "撤销标注")
                    }
                    IconButton(
                        onClick = { editor.redo() },
                        enabled = editor.canRedo,
                        modifier = Modifier.testTag("annotate-redo").semantics {
                            contentDescription = "重做标注"
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "重做标注")
                    }
                    TextButton(
                        onClick = { onSave(annotations.value) },
                        enabled = annotations.value.isNotEmpty(),
                        modifier = Modifier.testTag("annotate-save"),
                    ) {
                        Text("完成")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (preview == null)
                    if (error != null) Text(error!!) else CircularProgressIndicator()
                else {
                    val ratio = preview.width.toFloat() / preview.height
                    val fit =
                        if (maxWidth / maxHeight > ratio)
                            Modifier.fillMaxHeight().aspectRatio(ratio)
                        else Modifier.fillMaxWidth().aspectRatio(ratio)
                    val pointerModifier =
                        if (tool == "text")
                            Modifier.pointerInput(tool) {
                                    detectTapGestures { offset ->
                                        val point =
                                            PointN(
                                                (offset.x / size.width).coerceIn(0f, 1f),
                                                (offset.y / size.height).coerceIn(0f, 1f),
                                            )
                                        val existing =
                                            editor.annotations.firstOrNull {
                                                it.kind == "text" && it.hit(point)
                                            }
                                        if (existing != null) {
                                            textValue = existing.text
                                            editingText = existing
                                        } else {
                                            textAt = point
                                            textValue = ""
                                        }
                                    }
                                }
                                .testTag("annotation-canvas")
                        else
                            Modifier.pointerInput(tool, mosaicStyle, color, width) {
                                    fun toPoint(offset: Offset) =
                                        PointN(
                                            (offset.x / size.width).coerceIn(0f, 1f),
                                            (offset.y / size.height).coerceIn(0f, 1f),
                                        )
                                    // awaitFirstDown + drag keeps the exact finger-down point.
                                    // detectDragGestures would only report the position after the
                                    // touch slop, which visibly shaved the left/top edge off a
                                    // mosaic box on device.
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        val start = toPoint(down.position)
                                        when (tool) {
                                            "eraser" -> {
                                                erasing.clear()
                                                erasing.addAll(editor.idsAt(start))
                                            }
                                            "mosaic",
                                            "redact" ->
                                                current =
                                                    PageAnnotation(
                                                        if (tool == "mosaic") mosaicStyle
                                                        else "redact",
                                                        listOf(start, start),
                                                        color,
                                                        widthFor(tool, width),
                                                    )
                                            else ->
                                                current =
                                                    PageAnnotation(
                                                        if (tool == "highlight") "highlight"
                                                        else "pen",
                                                        listOf(start),
                                                        color,
                                                        widthFor(tool, width),
                                                    )
                                        }
                                        drag(down.id) { change ->
                                            change.consume()
                                            val point = toPoint(change.position)
                                            if (tool == "eraser")
                                                erasing.addAll(editor.idsAt(point))
                                            else
                                                current =
                                                    current?.let { annotation ->
                                                        if (annotation.isFreehand)
                                                            annotation.copy(
                                                                points = annotation.points + point
                                                            )
                                                        else
                                                            annotation.copy(
                                                                points =
                                                                    listOf(
                                                                        annotation.points.first(),
                                                                        point,
                                                                    )
                                                            )
                                                    }
                                        }
                                        if (tool == "eraser") {
                                            editor.removeIds(erasing.toSet())
                                            erasing.clear()
                                        } else current?.let { editor.add(it) }
                                        current = null
                                    }
                                }
                                .testTag("annotation-canvas")
                    Box(fit) {
                        Image(
                            preview.asImageBitmap(),
                            "标注页面 ${page.position + 1}",
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        Canvas(
                            Modifier.fillMaxSize().then(pointerModifier).semantics {
                                contentDescription =
                                    if (tool == "eraser") "标注画布 · 橡皮" else "标注画布"
                            }
                        ) {
                            drawIntoCanvas { canvas ->
                                PageAnnotations.drawInto(
                                    canvas.nativeCanvas,
                                    preview,
                                    size.width,
                                    size.height,
                                    editor.annotations + listOfNotNull(current),
                                )
                            }
                        }
                    }
                }
            }
            AnnotationToolbar(
                tool = tool,
                onTool = { tool = it },
                mosaicStyle = mosaicStyle,
                onMosaicStyle = { mosaicStyle = it },
                color = color,
                onColor = { color = it },
                width = width,
                onWidth = { width = it },
            )
        }
    }
    if (textAt != null || editingText != null)
        AlertDialog(
            onDismissRequest = {
                textAt = null
                editingText = null
            },
            title = { Text(if (editingText != null) "编辑文字批注" else "添加文字批注") },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("批注文字") },
                    modifier = Modifier.testTag("annotate-text-field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val editing = editingText
                        if (editing != null) editor.updateText(editing.id, textValue)
                        else
                            textAt?.let { point ->
                                if (textValue.isNotBlank())
                                    editor.add(
                                        PageAnnotation(
                                            "text",
                                            listOf(point),
                                            color,
                                            widthFor("text", width),
                                            textValue,
                                        )
                                    )
                            }
                        textAt = null
                        editingText = null
                    },
                    enabled = textValue.isNotBlank(),
                ) {
                    Text(if (editingText != null) "更新" else "添加")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        textAt = null
                        editingText = null
                    }
                ) {
                    Text("取消")
                }
            },
        )
}

private fun defaultWidth(tool: String): Float =
    when (tool) {
        "mosaic" -> .035f
        "text" -> .03f
        "highlight" -> .008f
        else -> .006f
    }

private fun widthFor(tool: String, width: Float): Float = width

private fun widthRange(tool: String): ClosedFloatingPointRange<Float> =
    when (tool) {
        "mosaic" -> .012f..08f
        "text" -> .015f..11f
        "highlight" -> .004f..02f
        else -> .002f..02f
    }

@Composable
private fun AnnotationToolbar(
    tool: String,
    onTool: (String) -> Unit,
    mosaicStyle: String,
    onMosaicStyle: (String) -> Unit,
    color: Int,
    onColor: (Int) -> Unit,
    width: Float,
    onWidth: (Float) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                annotationTools.forEach { (id, label) ->
                    AnnotationToolButton(
                        id = id,
                        label = label,
                        selected = tool == id,
                        onClick = { onTool(id) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tool == "eraser") {
                    // No width/colour for the eraser: only the gesture hint, so the second row does
                    // not grow when a tool has nothing to configure.
                    Text(
                        "拖动划过要擦除的标注",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (tool == "mosaic") {
                        FilterChip(
                            selected = mosaicStyle == "mosaic",
                            onClick = { onMosaicStyle("mosaic") },
                            label = { Text("马赛克") },
                            modifier = Modifier.testTag("annotate-style-mosaic"),
                        )
                        FilterChip(
                            selected = mosaicStyle == "redact",
                            onClick = { onMosaicStyle("redact") },
                            label = { Text("黑条") },
                            modifier = Modifier.testTag("annotate-style-redact"),
                        )
                    } else if (tool == "pen" || tool == "text") {
                        PageAnnotations.colors.forEachIndexed { index, argb ->
                            val chosen = argb == color
                            Box(
                                Modifier.padding(3.dp)
                                    .size(26.dp)
                                    .background(Color(argb), CircleShape)
                                    .border(
                                        if (chosen) 3.dp else 1.dp,
                                        if (chosen) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        CircleShape,
                                    )
                                    .clickable { onColor(argb) }
                                    .testTag("annotate-color-$index")
                                    .semantics {
                                        contentDescription =
                                            when (argb) {
                                                0xFF000000.toInt() -> "黑色"
                                                0xFFD32F2F.toInt() -> "红色"
                                                else -> "蓝色"
                                            }
                                        selected = chosen
                                    }
                            )
                        }
                    } else {
                        Text(
                            "高亮黄色",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = width.coerceIn(widthRange(tool)),
                        onValueChange = onWidth,
                        valueRange = widthRange(tool),
                        modifier = Modifier.weight(1f).testTag("annotate-width").semantics {
                            contentDescription = widthLabel(tool, mosaicStyle)
                        },
                    )
                    Text(
                        widthLabel(tool, mosaicStyle),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.widthIn(min = 46.dp),
                    )
                }
            }
        }
    }
}

private fun widthLabel(tool: String, mosaicStyle: String): String =
    when (tool) {
        "mosaic" -> if (mosaicStyle == "redact") "条粗细" else "块粗细"
        "text" -> "字号"
        else -> "笔宽"
    }

@Composable
private fun RowScope.AnnotationToolButton(
    id: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val icon =
        when (id) {
            "pen" -> Icons.Default.Create
            "highlight" -> Icons.Default.BorderColor
            "mosaic" -> Icons.Default.BlurOn
            "text" -> Icons.Default.TextFields
            else -> Icons.Default.Backspace
        }
    Column(
        Modifier.weight(1f)
            .clickable(onClick = onClick)
            .testTag("annotate-tool-$id")
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                this.selected = selected
                onClick(label) {
                    onClick()
                    true
                }
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint =
                    if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
