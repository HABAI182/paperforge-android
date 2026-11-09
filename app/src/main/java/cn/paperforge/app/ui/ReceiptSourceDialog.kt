package cn.paperforge.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.OcrFrame
import cn.paperforge.app.model.ReceiptFields
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only source check; uses the same cropped/rotated/dewarped geometry as the OCR frame. */
@Composable
fun ReceiptSourceDialog(
    vm: MainViewModel,
    document: DocumentRow,
    source: ReceiptFields.SourceLine,
    onDismiss: () -> Unit,
) {
    var preview by remember(source) { mutableStateOf<Pair<Bitmap, Bitmap>?>(null) }
    var problem by remember(source) { mutableStateOf<String?>(null) }
    var whole by remember(source) { mutableStateOf(source.corners.isEmpty()) }
    DisposableEffect(preview) {
        val owned = preview
        onDispose {
            owned?.first?.recycle()
            owned?.second?.takeIf { it !== owned.first }?.recycle()
        }
    }
    LaunchedEffect(source) {
        var generated: Pair<Bitmap, Bitmap>? = null
        try {
            withContext(Dispatchers.IO) {
                val bitmap =
                    if (document.kind == "scan") {
                        val page =
                            vm.repo.dao.pages(document.id).find { it.id == source.pageId }
                                ?: error("来源页已移除")
                        require(
                            source.geometrySignature == null ||
                                source.geometrySignature == OcrFrame.signature(page)
                        ) {
                            "来源页的裁切或方向已变化，请重新提取票据字段"
                        }
                        ScanProcessor.render(
                            page.copy(filter = "ORIGINAL", brightness = 0f, contrast = 1f),
                            2000,
                        )
                    } else
                        PdfSession(File(requireNotNull(document.pdfPath))).use {
                            it.render(source.page, 2000).copy(Bitmap.Config.ARGB_8888, true)
                        }
                try {
                    val polygon = source.corners
                    val detail =
                        if (polygon.size >= 4) {
                            val left =
                                floor(
                                        (polygon.minOf { it.x } - .025f).coerceIn(0f, 1f) *
                                            bitmap.width
                                    )
                                    .toInt()
                                    .coerceIn(0, bitmap.width - 1)
                            val top =
                                floor(
                                        (polygon.minOf { it.y } - .03f).coerceIn(0f, 1f) *
                                            bitmap.height
                                    )
                                    .toInt()
                                    .coerceIn(0, bitmap.height - 1)
                            val right =
                                ceil(
                                        (polygon.maxOf { it.x } + .025f).coerceIn(0f, 1f) *
                                            bitmap.width
                                    )
                                    .toInt()
                                    .coerceIn(left + 1, bitmap.width)
                            val bottom =
                                ceil(
                                        (polygon.maxOf { it.y } + .03f).coerceIn(0f, 1f) *
                                            bitmap.height
                                    )
                                    .toInt()
                                    .coerceIn(top + 1, bitmap.height)
                            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                        } else bitmap
                    if (polygon.size >= 4) {
                        Canvas(bitmap)
                            .drawRect(
                                polygon.minOf { it.x } * bitmap.width,
                                polygon.minOf { it.y } * bitmap.height,
                                polygon.maxOf { it.x } * bitmap.width,
                                polygon.maxOf { it.y } * bitmap.height,
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.rgb(0, 133, 105)
                                    style = Paint.Style.STROKE
                                    strokeWidth = 4f
                                },
                            )
                    }
                    generated = bitmap to detail
                } catch (failure: Throwable) {
                    bitmap.recycle()
                    throw failure
                }
            }
            preview = generated
            generated = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            problem = failure.message ?: "来源图暂时无法显示"
        } finally {
            generated?.first?.recycle()
            generated?.second?.takeIf { it !== generated?.first }?.recycle()
        }
    }
    SpaciousToolDialog(
        onDismissRequest = onDismiss,
        title = { Text("核对来源 · 第 ${source.page + 1} 页") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(source.text, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${source.engine.ifBlank { "文字来源未记录" }} · 第 ${source.line + 1} 行" +
                        (source.confidence?.let { " · OCR ${(it * 100).toInt()}%" }
                            ?: " · 未提供 OCR 置信度"),
                    style = MaterialTheme.typography.bodySmall,
                )
                preview?.let { pair ->
                    Image(
                        (if (whole) pair.first else pair.second).asImageBitmap(),
                        "票据字段原图",
                        Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 380.dp),
                    )
                    if (source.corners.isNotEmpty())
                        TextButton(onClick = { whole = !whole }) {
                            Text(if (whole) "放大字段" else "查看整页位置")
                        }
                    else Text("此文字没有来源坐标，显示整页供核对。", style = MaterialTheme.typography.bodySmall)
                }
                    ?: if (problem == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else Text(problem!!)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回核对表") } },
    )
}
