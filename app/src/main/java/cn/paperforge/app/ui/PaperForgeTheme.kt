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
                onPrimaryContainer = lerp(accent, Color.White, .8f),
                secondary = lerp(accent, Color.White, .55f),
                secondaryContainer = lerp(accent, Color.Black, .65f),
                onSecondaryContainer = Color(0xFFE2EAE6),
                background = Color(0xFF111715),
                surface = Color(0xFF111715),
                surfaceContainerLowest = Color(0xFF0D1210),
                surfaceContainerLow = Color(0xFF19211E),
                surfaceContainer = Color(0xFF1D2622),
                surfaceContainerHigh = Color(0xFF26302B),
                surfaceContainerHighest = Color(0xFF303B35),
                onSurface = Color(0xFFE4EAE6),
                onSurfaceVariant = Color(0xFFBCC9C0),
                outline = Color(0xFF87958C),
                outlineVariant = Color(0xFF3B4941),
            )
        else
            lightColorScheme(
                primary = accent,
                primaryContainer = lerp(accent, Color.White, .82f),
                onPrimaryContainer = lerp(accent, Color.Black, .5f),
                secondary = accent,
                secondaryContainer = lerp(accent, Color.White, .88f),
                onSecondaryContainer = lerp(accent, Color.Black, .45f),
                background = Color(0xFFFAF9F5),
                surface = Color(0xFFFAF9F5),
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = Color(0xFFF1F3EE),
                surfaceContainer = Color(0xFFECEFE9),
                surfaceContainerHigh = Color(0xFFE6EBE3),
                surfaceContainerHighest = Color(0xFFE0E7DD),
                onSurface = Color(0xFF1C2822),
                onSurfaceVariant = Color(0xFF536258),
                outline = Color(0xFF75857A),
                outlineVariant = Color(0xFFCFD9CF),
            )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides
            Density(density.density, density.fontScale * UserSettings.number("fontScale", 1f))
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
