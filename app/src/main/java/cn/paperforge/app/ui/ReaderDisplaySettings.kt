package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.paperforge.app.reader.PageInsets
import cn.paperforge.app.reader.ReaderViewOptions

@Composable
fun ReaderDisplaySettings(
    options: ReaderViewOptions,
    page: Int,
    onChange: (ReaderViewOptions) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var scope by remember(page) { mutableStateOf("page:$page") }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("宽屏面对页", Modifier.weight(1f))
        Switch(
            options.facingPages,
            { onChange(options.copy(facingPages = it)) },
            Modifier.semantics { contentDescription = "宽屏面对页" },
        )
    }
    if (options.facingPages)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("封面单页", Modifier.weight(1f))
            Switch(
                options.coverAlone,
                { onChange(options.copy(coverAlone = it)) },
                Modifier.semantics { contentDescription = "封面单页" },
            )
        }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            !options.rightToLeft,
            { onChange(options.copy(rightToLeft = false)) },
            label = { Text("从左到右") },
        )
        FilterChip(
            options.rightToLeft,
            { onChange(options.copy(rightToLeft = true)) },
            label = { Text("从右到左") },
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            !options.fitWidth,
            { onChange(options.copy(fitWidth = false)) },
            label = { Text("适页") },
        )
        FilterChip(
            options.fitWidth,
            { onChange(options.copy(fitWidth = true)) },
            label = { Text("适宽") },
        )
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起显示裁边" else "显示裁边") }
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("图片保留原色", Modifier.weight(1f))
        Switch(
            options.preserveImageColor,
            { onChange(options.copy(preserveImageColor = it)) },
            Modifier.semantics { contentDescription = "图片保留原色" },
        )
    }
    if (expanded) {
        Text("仅隐藏阅读边距，源 PDF 和导出内容不变。", style = MaterialTheme.typography.bodySmall)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("page:$page" to "当前页", "all" to "全篇", "odd" to "奇数页", "even" to "偶数页").forEach {
                (key, title) ->
                FilterChip(scope == key, { scope = key }, label = { Text(title) })
            }
        }
        val values = options.trims[scope] ?: PageInsets()
        fun save(value: PageInsets) {
            onChange(options.copy(trims = options.trims + (scope to value)))
        }
        Text("左 %.0f%%".format(values.left * 100))
        Slider(values.left, { save(values.copy(left = it)) }, valueRange = 0f..0.4f)
        Text("上 %.0f%%".format(values.top * 100))
        Slider(values.top, { save(values.copy(top = it)) }, valueRange = 0f..0.4f)
        Text("右 %.0f%%".format(values.right * 100))
        Slider(values.right, { save(values.copy(right = it)) }, valueRange = 0f..0.4f)
        Text("下 %.0f%%".format(values.bottom * 100))
        Slider(values.bottom, { save(values.copy(bottom = it)) }, valueRange = 0f..0.4f)
        TextButton(onClick = { onChange(options.copy(trims = options.trims - scope)) }) {
            Text("清除此范围裁边")
        }
    }
}
