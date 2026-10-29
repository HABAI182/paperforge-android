package cn.paperforge.app.ui

import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings

@Composable
fun PaperForgeTheme(forceDarkSystemBars: Boolean = false, content: @Composable () -> Unit) {
    val revision by UserSettings.revision.collectAsState()
    val theme = remember(revision) { UserSettings[SettingKeys.Theme] }
    val dark =
        when (theme) {
            "DARK",
            "AMOLED",
            "MIDNIGHT" -> true
            "LIGHT",
            "SEPIA" -> false
            else -> isSystemInDarkTheme()
        }
    val activity = LocalActivity.current as? androidx.activity.ComponentActivity
    val darkBars = dark || forceDarkSystemBars
    SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) {
                    darkBars
                },
            navigationBarStyle =
                SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { darkBars },
        )
    }
    val colors = cn.paperforge.theme.paperColorScheme(UserSettings[SettingKeys.Accent], dark, theme)
    val selectedColors =
        if (
            UserSettings[SettingKeys.DynamicColor] &&
                theme !in setOf("SEPIA", "MIDNIGHT") &&
                android.os.Build.VERSION.SDK_INT >= 31
        ) {
            if (dark) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        } else colors
    val finalColors =
        if (theme == "AMOLED")
            selectedColors.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLowest = Color.Black,
            )
        else selectedColors
    val typography =
        Typography(
            displayLarge =
                TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold),
            displayMedium =
                TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
            displaySmall =
                TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
            headlineLarge =
                TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
            headlineMedium =
                TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall =
                TextStyle(fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
            titleLarge =
                TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            titleMedium =
                TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
            titleSmall =
                TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge =
                TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
            labelMedium =
                TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
            labelSmall =
                TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        )
    val density = LocalDensity.current
    val customScale = UserSettings[SettingKeys.FontScale].coerceIn(.85f, 1.3f)
    val effectiveFontScale =
        if (density.fontScale >= 2f) density.fontScale
        else (density.fontScale * customScale).coerceAtMost(2f)
    val reducedMotion =
        UserSettings[SettingKeys.ReduceMotion] ||
            !android.animation.ValueAnimator.areAnimatorsEnabled()
    CompositionLocalProvider(
        LocalPaperMotion provides PaperMotion(reducedMotion),
        LocalDensity provides Density(density.density, effectiveFontScale),
    ) {
        MaterialTheme(
            colorScheme = finalColors,
            typography = typography,
            shapes =
                Shapes(
                    small = RoundedCornerShape(10.dp),
                    medium = RoundedCornerShape(14.dp),
                    large = RoundedCornerShape(20.dp),
                    extraLarge = RoundedCornerShape(28.dp),
                ),
            content = content,
        )
    }
}
