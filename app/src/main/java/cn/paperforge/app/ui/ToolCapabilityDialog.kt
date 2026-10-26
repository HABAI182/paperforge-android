package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.UserSettings
import cn.paperforge.app.model.ToolCapability

@Composable
fun ToolPinButton(id: String, title: String, pinned: Boolean) {
    IconButton(onClick = { UserSettings.toggleTool(id) }) {
        Icon(
            if (pinned) Icons.Default.Star else Icons.Default.StarBorder,
            if (pinned) "取消固定$title" else "固定$title",
            tint =
                if (pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ToolCapabilityDialog(capability: ToolCapability, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(capability.title) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                listOf(
                        "输入" to capability.input,
                        "输出" to capability.output,
                        "处理方式" to capability.engine,
                        "可调内容" to capability.parameters,
                        "结果说明" to capability.boundary,
                    )
                    .forEach { (label, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
