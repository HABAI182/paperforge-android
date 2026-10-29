package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.ocr.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OcrOptionsControl() {
    val revision by UserSettings.revision.collectAsState()
    val selected = remember(revision) { OcrOptions.current().mode }
    val paddle = remember(revision) { UserSettings[SettingKeys.OcrBackend] == "PADDLE" }
    val order = remember(revision) { OcrOptions.current().order }
    var orderMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("文字识别", style = MaterialTheme.typography.labelLarge)
        if (!paddle)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OcrMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selected == mode,
                        onClick = { UserSettings.set(SettingKeys.OcrMode, mode.name) },
                        label = { Text(mode.label) },
                    )
                }
            }
        Box {
            TextButton(onClick = { orderMenu = true }) { Text("排序 · ${order.label}") }
            DropdownMenu(orderMenu, { orderMenu = false }) {
                OcrOrder.entries.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(value.label) },
                        onClick = {
                            UserSettings.set(SettingKeys.OcrOrder, value.name)
                            orderMenu = false
                        },
                    )
                }
            }
        }
        Text(
            if (paddle) "当前模型包使用单路行级识别；识别语言由所选模型包决定。"
            else
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
