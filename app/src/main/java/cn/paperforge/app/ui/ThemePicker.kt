package cn.paperforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.theme.paperAccents

@Composable
internal fun ThemePicker() {
    val revision by UserSettings.revision.collectAsState()
    val mode = remember(revision) { UserSettings[SettingKeys.Theme] }
    val accent = remember(revision) { UserSettings[SettingKeys.Accent] }
    val themes =
        listOf(
            WorkspaceSection("SYSTEM", "跟随系统", "自动切换浅色与深色", Icons.Default.BrightnessAuto),
            WorkspaceSection("LIGHT", "明亮", "清爽浅色与柔和卡片", Icons.Default.LightMode),
            WorkspaceSection("DARK", "深色", "低亮背景与清晰层次", Icons.Default.DarkMode),
            WorkspaceSection("AMOLED", "纯黑", "黑色画布与柔和文字", Icons.Default.Contrast),
            WorkspaceSection("SEPIA", "暖纸", "米色纸感与咖啡色调", Icons.Default.MenuBook),
            WorkspaceSection("MIDNIGHT", "午夜蓝", "深蓝背景与冷色层次", Icons.Default.NightsStay),
        )
    Text(
        "当前外观：${themes.find { it.id == mode }?.title}",
        style = MaterialTheme.typography.labelLarge,
    )
    SectionCardGrid(themes, selectedId = mode) {
        UserSettings.set(SettingKeys.Theme, it)
        if (it in setOf("SEPIA", "MIDNIGHT")) UserSettings.set(SettingKeys.DynamicColor, false)
    }
    Text(
        "跟随系统只同步明暗，主题色独立选择。暖纸与午夜蓝使用专属配色。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("主题色", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        paperAccents.forEach { (key, value) ->
            FilterChip(
                accent == key && !UserSettings[SettingKeys.DynamicColor],
                onClick = {
                    UserSettings.set(SettingKeys.Accent, key)
                    UserSettings.set(SettingKeys.DynamicColor, false)
                    if (mode in setOf("SEPIA", "MIDNIGHT"))
                        UserSettings.set(SettingKeys.Theme, "SYSTEM")
                },
                label = { Text(value.first) },
                leadingIcon = { Box(Modifier.size(14.dp).background(value.second, CircleShape)) },
            )
        }
    }
}
