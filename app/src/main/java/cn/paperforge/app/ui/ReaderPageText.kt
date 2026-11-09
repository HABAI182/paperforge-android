package cn.paperforge.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.model.PageViewport
import cn.paperforge.app.model.PointN
import cn.paperforge.app.pdf.PageTextSource
import cn.paperforge.app.pdf.ReaderDocument
import cn.paperforge.app.pdf.ReaderTextBlock
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a page is shown: the scan itself, or the recognized text in place of it. */
internal enum class ReaderTextView { ORIGINAL, TEXT }

private fun textPaint() =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(24, 24, 24)
        typeface = Typeface.DEFAULT
        isSubpixelText = true
    }

/**
 * Draws [text] in a box at the size the box implies: its height sets the font, and a small horizontal
 * stretch fits it to the width, so words land where they were found. A tall narrow box is vertical text.
 */
internal fun drawTextInBox(
    canvas: android.graphics.Canvas,
    paint: Paint,
    text: String,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    val width = right - left
    val height = bottom - top
    val value = text.trim()
    if (value.isEmpty() || width <= 0f || height <= 0f) return
    paint.textScaleX = 1f
    if (height > 2.2f * width && value.length >= 2) {
        // Vertical text: one character under another.
        val step = height / value.length
        paint.textSize = min(width, step) * .9f
        val metrics = paint.fontMetrics
        value.forEachIndexed { index, char ->
            val glyph = char.toString()
            val x = left + (width - paint.measureText(glyph)) / 2
            val y = top + step * index + (step - metrics.ascent - metrics.descent) / 2
            canvas.drawText(glyph, x, y, paint)
        }
        return
    }
    paint.textSize = height * .82f
    val measured = paint.measureText(value)
    if (measured > 0f) {
        val stretch = width / measured
        if (stretch < .55f) paint.textSize *= stretch / .55f
        paint.textScaleX = stretch.coerceIn(.55f, 1.4f)
    }
    val metrics = paint.fontMetrics
    canvas.drawText(value, left, top + (height - metrics.ascent - metrics.descent) / 2, paint)
    paint.textScaleX = 1f
}

private fun DrawScope.drawBlock(paint: Paint, block: ReaderTextBlock, viewport: PageViewport) {
    fun x(value: Float) = viewport.view(PointN(value, block.top)).x * size.width
    val top = viewport.view(PointN(block.left, block.top)).y * size.height
    val bottom = viewport.view(PointN(block.left, block.bottom)).y * size.height
    drawIntoCanvas { holder ->
        val canvas = holder.nativeCanvas
        if (block.units.isNotEmpty()) {
            // Word by word, all at the line's height, so a comma does not shrink to its own small box.
            block.units.forEach { unit ->
                if (unit.end <= block.text.length)
                    drawTextInBox(
                        canvas,
                        paint,
                        block.text.substring(unit.start, unit.end),
                        x(unit.left),
                        top,
                        x(unit.right),
                        bottom,
                    )
            }
        } else {
            val lines = block.text.split('\n').filter { it.isNotBlank() }
            lines.forEachIndexed { index, line ->
                val rowHeight = (bottom - top) / max(1, lines.size)
                drawTextInBox(
                    canvas,
                    paint,
                    line,
                    x(block.left),
                    top + rowHeight * index,
                    x(block.right),
                    top + rowHeight * (index + 1),
                )
            }
        }
    }
}

/** A sheet of paper with the page's recognized text at the places it was found. */
@Composable
internal fun ReaderRecognizedTextLayer(file: File, page: Int, viewport: PageViewport) {
    val context = LocalContext.current
    val app = context.applicationContext as PaperForgeApp
    val blocks by
        produceState<List<ReaderTextBlock>?>(null, file, page) {
            value =
                runCatching { PageTextSource.load(context, file, page, app.ocrEngine) }
                    .getOrElse {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        emptyList()
                    }
        }
    val paint = remember { textPaint() }
    Box(Modifier.fillMaxSize().background(Color.White).testTag("reader-page-text-$page")) {
        val shown = blocks
        when {
            shown == null ->
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("正在识别本页文字…", Modifier.padding(top = 8.dp), color = Color.DarkGray)
                }
            shown.isEmpty() ->
                Text("这一页没有识别到文字", Modifier.align(Alignment.Center), color = Color.DarkGray)
            else -> Canvas(Modifier.fillMaxSize()) { shown.forEach { drawBlock(paint, it, viewport) } }
        }
    }
}

/** Switches the page between the scan and its text, and opens the overview of the whole document. */
@Composable
internal fun ReaderTextSwitch(
    view: ReaderTextView,
    onView: (ReaderTextView) -> Unit,
    onOverview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Row(
            Modifier.padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(view == ReaderTextView.ORIGINAL, { onView(ReaderTextView.ORIGINAL) }, label = { Text("原图") })
            FilterChip(view == ReaderTextView.TEXT, { onView(ReaderTextView.TEXT) }, label = { Text("文字") })
            TextButton(onClick = onOverview) { Text("全文") }
        }
    }
}

/** All the document's text in one place, page by page. Pages are read as they scroll into view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAllTextSheet(
    file: File,
    pageCount: Int,
    currentPage: Int,
    onGoTo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PaperForgeApp
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val texts = remember(file) { mutableStateMapOf<Int, String>() }
    // One page at a time: recognition is heavy, and scrolling must not start many at once.
    val gate = remember { Mutex() }
    var copying by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf("") }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = currentPage.coerceIn(0, max(0, pageCount - 1)))
    suspend fun read(page: Int): String =
        texts[page]
            ?: gate.withLock {
                texts[page]
                    ?: runCatching {
                            ReaderDocument.order(PageTextSource.load(context, file, page, app.ocrEngine), 0)
                                .joinToString("\n") { it.text }
                        }
                        .getOrElse {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            ""
                        }
                        .also { texts[page] = it }
            }
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxHeight(.9f).padding(horizontal = 16.dp).testTag("reader-all-text")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全部文字 · 共 $pageCount 页", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(
                    onClick = {
                        scope.launch {
                            for (page in 0 until pageCount) {
                                copying = page + 1
                                read(page)
                            }
                            copying = null
                            clipboard.setText(
                                AnnotatedString(
                                    (0 until pageCount).mapNotNull { texts[it]?.takeIf(String::isNotBlank) }.joinToString("\n\n")
                                )
                            )
                            notice = "已复制全部文字"
                        }
                    },
                    enabled = copying == null,
                ) {
                    Text("复制全部")
                }
            }
            copying?.let {
                LinearProgressIndicator({ it.toFloat() / max(1, pageCount) }, Modifier.fillMaxWidth())
                Text("正在读取第 $it / $pageCount 页", style = MaterialTheme.typography.labelSmall)
            }
            if (notice.isNotBlank() && copying == null)
                Text(notice, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(pageCount, key = { it }) { page ->
                    LaunchedEffect(page) { read(page) }
                    val text = texts[page]
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "第 ${page + 1} 页",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { onGoTo(page) }) { Text("看这一页") }
                            }
                            when {
                                text == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                                text.isBlank() ->
                                    Text(
                                        "这一页没有识别到文字",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                else ->
                                    SelectionContainer {
                                        Text(text, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
}
