package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import java.io.File

@Composable
fun PdfCompressionDialog(vm: MainViewModel, file: File?, onDismiss: () -> Unit) {
    val busy by vm.busy.collectAsState()
    var target by remember { mutableStateOf(false) }
    var kilobytes by remember { mutableStateOf("1024") }
    var quality by remember { mutableIntStateOf(75) }
    val bytes = kilobytes.toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE / 1024) }?.times(1024)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩 PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前 ${(file?.length() ?: 0)/1024} KB · 保留原生文字、链接和表单")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!target, { target = false }, label = { Text("按画质") })
                    FilterChip(target, { target = true }, label = { Text("目标体积") })
                }
                if (target) {
                    OutlinedTextField(
                        kilobytes,
                        { kilobytes = it },
                        label = { Text("目标大小 · KB") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = bytes == null,
                    )
                    Text(
                        "尽量在保留内容的前提下达到目标；结果会注明实际体积与是否达标。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("图像质量 $quality")
                    Slider(quality.toFloat(), { quality = it.toInt() }, valueRange = 10f..95f)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(90 to "清晰", 75 to "均衡", 45 to "小体积").forEach { (q, label) ->
                            FilterChip(quality == q, { quality = q }, label = { Text(label) })
                        }
                    }
                }
                Text("生成后可预览实际 PDF；无法减小时保留原件副本。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    if (target) bytes?.let(vm::compressTarget) else vm.compress(quality)
                },
                enabled = !busy && file != null && (!target || bytes != null),
            ) {
                Text("生成副本")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
