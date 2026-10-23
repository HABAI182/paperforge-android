package cn.paperforge.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import cn.paperforge.app.UserSettings

@Composable
fun PaperForgeTheme(content: @Composable () -> Unit) {
    val revision by UserSettings.revision.collectAsState()
    val theme = remember(revision) { UserSettings.string("theme", "SYSTEM") }
    val dark =
        when (theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemInDarkTheme()
        }
    val accent =
        when (UserSettings.string("accent", "GREEN")) {
            "BLUE" -> Color(0xFF225BAA)
            "PURPLE" -> Color(0xFF7250A0)
            "ORANGE" -> Color(0xFFA54B15)
            else -> Color(0xFF176B54)
        }
    val colors =
        if (dark)
            darkColorScheme(
                primary = lerp(accent, Color.White, .48f),
                onPrimary = Color(0xFF102019),
                primaryContainer = lerp(accent, Color.Black, .55f),
            )
        else
            lightColorScheme(
                primary = accent,
                primaryContainer = lerp(accent, Color.White, .82f),
                secondary = accent,
                background = Color(0xFFFAF9F5),
                surface = Color(0xFFFAF9F5),
                surfaceContainerLow = Color(0xFFF0F1EA),
            )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides
            Density(density.density, density.fontScale * UserSettings.number("fontScale", 1f))
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
