package cn.paperforge.app.ui

import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import cn.paperforge.app.pdf.LongImageOptions

@Composable
fun LongImageControls(
    value: LongImageOptions,
    onChange: (LongImageOptions) -> Unit,
    onValid: (Boolean) -> Unit,
) {
    var width by remember { mutableStateOf(value.width.toString()) }
    var gap by remember { mutableStateOf(value.gap.toString()) }
    var height by remember { mutableStateOf(value.segmentHeight.toString()) }
    var color by remember { mutableStateOf("%06X".format(value.background and 0xFFFFFF)) }
    fun update() {
        val w = width.toIntOrNull()
        val g = gap.toIntOrNull()
        val h = height.toIntOrNull()
        val c = runCatching { Color.parseColor("#$color") }.getOrNull()
        val valid =
            w != null &&
                w in 128..4096 &&
                g != null &&
                g in 0..1024 &&
                h != null &&
                h in 256..30000 &&
                c != null
        onValid(valid)
        if (valid)
            onChange(value.copy(width = w!!, gap = g!!, segmentHeight = h!!, background = c!!))
    }
    LaunchedEffect(Unit) { update() }
    OutlinedTextField(
        width,
        {
            width = it
            update()
        },
        label = { Text("长图宽度 · 128–4096 像素") },
        singleLine = true,
    )
    OutlinedTextField(
        gap,
        {
            gap = it
            update()
        },
        label = { Text("页面 / 图片间距 · 0–1024 像素") },
        singleLine = true,
    )
    OutlinedTextField(
        height,
        {
            height = it
            update()
        },
        label = { Text("分段高度上限 · 256–30000 像素") },
        singleLine = true,
    )
    Text(
        "当前每段最多 ${minOf(value.segmentHeight,16_000_000/value.width)} 像素高；分段连续保留内容。",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        color,
        {
            color = it
            update()
        },
        label = { Text("间距底色 · RGB 十六进制") },
        singleLine = true,
    )
    Row {
        FilterChip(
            value.format == "png",
            { onChange(value.copy(format = "png")) },
            label = { Text("PNG") },
        )
        FilterChip(
            value.format == "jpg",
            { onChange(value.copy(format = "jpg")) },
            label = { Text("JPG") },
        )
    }
    if (value.format == "jpg") {
        Text("JPG 质量 ${value.quality}")
        Slider(
            value.quality.toFloat(),
            { onChange(value.copy(quality = it.toInt())) },
            valueRange = 1f..100f,
        )
    }
}
