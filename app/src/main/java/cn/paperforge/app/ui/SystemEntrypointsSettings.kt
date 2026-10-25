package cn.paperforge.app.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.R
import cn.paperforge.app.system.ScanTileService
import cn.paperforge.app.system.SystemEntrypoints

@Composable
fun SystemEntrypointsSettings() {
    var showing by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showing = true }, modifier = Modifier.fillMaxWidth()) {
        Text("桌面快捷方式与系统入口")
    }
    if (showing)
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text("桌面与系统入口") },
            text = {
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EntrypointsContent()
                }
            },
            confirmButton = { TextButton(onClick = { showing = false }) { Text("完成") } },
        )
}

@Composable
private fun EntrypointsContent() {
    val context = LocalContext.current
    var notice by remember { mutableStateOf("") }
    Text("长按应用图标可直接扫描、识字或继续阅读；也可分别添加到桌面。", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
                SystemEntrypoints.SCAN to "扫描",
                SystemEntrypoints.OCR to "识字",
                SystemEntrypoints.CONTINUE to "继续阅读",
            )
            .forEach { (action, label) ->
                OutlinedButton(
                    onClick = {
                        notice =
                            if (
                                runCatching { SystemEntrypoints.pin(context, action) }
                                    .getOrDefault(false)
                            )
                                "请在系统桌面确认添加"
                            else "当前桌面不支持固定快捷方式，可长按应用图标使用"
                    }
                ) {
                    Text("添加 $label")
                }
            }
    }
    if (Build.VERSION.SDK_INT >= 33)
        OutlinedButton(
            onClick = {
                context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(context, ScanTileService::class.java),
                    context.getString(R.string.shortcut_scan),
                    Icon.createWithResource(context, R.drawable.ic_shortcut_scan),
                    context.mainExecutor,
                ) { result ->
                    notice =
                        if (
                            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                                result ==
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                        )
                            "扫描按钮已在快捷设置中"
                        else "本次未添加，可在快捷设置编辑面板添加「扫描纸张」"
                }
            }
        ) {
            Text("添加扫描到快捷设置")
        }
    Text("在桌面的小组件列表中可添加「继续阅读」，上次页码会在离开阅读后更新。", style = MaterialTheme.typography.bodySmall)
    if (notice.isNotBlank())
        Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
}
