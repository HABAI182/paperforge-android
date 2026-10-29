package cn.paperforge.network

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.work.*
import kotlinx.coroutines.*

internal fun ComponentActivity.networkContent(content: @Composable () -> Unit) {
    val dark =
        when (intent.getStringExtra("paperforgeTheme")) {
            "DARK" -> true
            "LIGHT" -> false
            else ->
                resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    val bar =
        if (dark) SystemBarStyle.dark(0xff101815.toInt())
        else SystemBarStyle.light(0xfff6f8f2.toInt(), 0xff101815.toInt())
    enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
    setContent {
        val colors =
            if (dark)
                darkColorScheme(
                    primary = Color(0xffa1d5c4),
                    onPrimary = Color(0xff09382d),
                    primaryContainer = Color(0xff204f42),
                    onPrimaryContainer = Color(0xffccecdf),
                    secondary = Color(0xffb7cbbf),
                    onSecondary = Color(0xff22352a),
                    secondaryContainer = Color(0xff344b3f),
                    onSecondaryContainer = Color(0xffd7e9dc),
                    background = Color(0xff101815),
                    onBackground = Color(0xffe5ece5),
                    surface = Color(0xff101815),
                    onSurface = Color(0xffe5ece5),
                    surfaceVariant = Color(0xff2c3a33),
                    onSurfaceVariant = Color(0xffbdc9c0),
                    outline = Color(0xff8a998e),
                    surfaceContainerLowest = Color(0xff0b130f),
                    surfaceContainerLow = Color(0xff15211a),
                    surfaceContainer = Color(0xff1b2a21),
                    surfaceContainerHigh = Color(0xff24342b),
                    surfaceContainerHighest = Color(0xff2c3a33),
                )
            else
                lightColorScheme(
                    primary = Color(0xff226c5b),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xffccecdf),
                    onPrimaryContainer = Color(0xff10382a),
                    secondary = Color(0xff476354),
                    onSecondary = Color.White,
                    secondaryContainer = Color(0xffd7e9dc),
                    onSecondaryContainer = Color(0xff23372b),
                    background = Color(0xfff6f8f2),
                    onBackground = Color(0xff17261c),
                    surface = Color(0xfff6f8f2),
                    onSurface = Color(0xff17261c),
                    surfaceVariant = Color(0xffe1eae1),
                    onSurfaceVariant = Color(0xff425249),
                    outline = Color(0xff6f8175),
                    surfaceContainerLowest = Color.White,
                    surfaceContainerLow = Color(0xfff0f5ec),
                    surfaceContainer = Color(0xffeaf0e6),
                    surfaceContainerHigh = Color(0xffe2eadd),
                    surfaceContainerHighest = Color(0xffdbe5d5),
                )
        val density = LocalDensity.current
        val factor = intent.getFloatExtra("paperforgeFontScale", 1f).coerceIn(.85f, 1.3f)
        CompositionLocalProvider(
            LocalDensity provides
                Density(
                    density.density,
                    if (density.fontScale >= 2f) density.fontScale
                    else (density.fontScale * factor).coerceAtMost(2f),
                )
        ) {
            MaterialTheme(colorScheme = colors) { content() }
        }
    }
}
