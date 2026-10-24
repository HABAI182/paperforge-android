package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import cn.paperforge.app.UserSettings
import cn.paperforge.app.ocr.*

@Composable
fun OcrOptionsControl() {
    val revision by UserSettings.revision.collectAsState()
    val selected = remember(revision) { OcrOptions.current().mode }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("文字识别", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcrMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { UserSettings.set("ocrMode", mode.name) },
                    label = { Text(mode.label) },
                )
            }
        }
        Text(
            when (selected) {
                OcrMode.FAST -> "一次识别，适合清晰正向文字；方向不对时先旋转页面。"
                OcrMode.BALANCED -> "优先识别原图，只对疑难文字局部补识别。"
                OcrMode.DETAILED -> "尝试更多采样、增强与局部识别，适合难页，耗时更长。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
