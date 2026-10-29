package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings

object PaperSpace {
    val tiny = 4.dp
    val small = 8.dp
    val regular = 16.dp
    val large = 24.dp
    val section = 32.dp
}

data class PaperMotion(val reduced: Boolean = false) {
    val stateMs
        get() = if (reduced) 0 else 120

    val panelMs
        get() = if (reduced) 0 else 200

    val screenMs
        get() = if (reduced) 0 else 280
}

val LocalPaperMotion = staticCompositionLocalOf { PaperMotion() }

/** Optional feedback for explicit selection, rather than every toolbar tap. */
@Composable
internal fun selectionFeedback(): () -> Unit {
    val feedback = LocalHapticFeedback.current
    val revision by UserSettings.revision.collectAsState()
    val enabled = remember(revision) { UserSettings[SettingKeys.Haptics] }
    return { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
}

@Composable
internal fun PaperEmptyState(
    icon: ImageVector,
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(PaperSpace.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PaperSpace.small),
    ) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) OutlinedButton(onAction) { Text(actionLabel) }
    }
}

@Composable
internal fun PaperStatusLabel(label: String, attention: Boolean = false, error: Boolean = false) {
    val background =
        when {
            error -> MaterialTheme.colorScheme.errorContainer
            attention -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
    val foreground =
        when {
            error -> MaterialTheme.colorScheme.onErrorContainer
            attention -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        }
    Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp).semantics {
                stateDescription = label
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
