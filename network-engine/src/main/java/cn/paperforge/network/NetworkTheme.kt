package cn.paperforge.network

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

internal fun ComponentActivity.networkContent(content: @Composable () -> Unit) {
    setContent {
        val integrated = remember { NetworkFiles.integrated(this) }
        val prefs = remember { getSharedPreferences("preferences", 0) }
        var revision by remember { mutableIntStateOf(0) }
        DisposableEffect(prefs) {
            val listener =
                android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    revision++
                }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        val theme =
            remember(revision) {
                if (integrated) prefs.getString("theme", "SYSTEM") ?: "SYSTEM"
                else intent.getStringExtra("paperforgeTheme") ?: "SYSTEM"
            }
        val accent =
            remember(revision) {
                if (integrated) prefs.getString("accent", "GREEN") ?: "GREEN"
                else intent.getStringExtra("paperforgeAccent") ?: "GREEN"
            }
        val dynamic =
            remember(revision) {
                if (integrated) prefs.getBoolean("dynamicColor", false)
                else intent.getBooleanExtra("paperforgeDynamicColor", false)
            }
        val dark =
            when (theme) {
                "DARK",
                "AMOLED",
                "MIDNIGHT" -> true
                "LIGHT",
                "SEPIA" -> false
                else -> isSystemInDarkTheme()
            }
        SideEffect {
            val style =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) {
                    dark
                }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
        val standard = cn.paperforge.theme.paperColorScheme(accent, dark, theme)
        val colors =
            if (
                dynamic &&
                    theme !in setOf("SEPIA", "MIDNIGHT") &&
                    android.os.Build.VERSION.SDK_INT >= 31
            ) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else standard
        val density = LocalDensity.current
        val factor =
            (if (integrated) prefs.getFloat("fontScale", 1f)
                else intent.getFloatExtra("paperforgeFontScale", 1f))
                .coerceIn(.85f, 1.3f)
        CompositionLocalProvider(
            LocalDensity provides
                Density(
                    density.density,
                    if (density.fontScale >= 2f) density.fontScale
                    else (density.fontScale * factor).coerceAtMost(2f),
                )
        ) {
            MaterialTheme(
                colorScheme =
                    if (theme == "AMOLED")
                        colors.copy(
                            background = Color.Black,
                            surface = Color.Black,
                            surfaceContainerLowest = Color.Black,
                        )
                    else colors
            ) {
                content()
            }
        }
    }
}
