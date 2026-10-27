package cn.paperforge.app.ui

import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.paperforge.app.UserSettings

@Composable
fun PaperForgeTheme(forceDarkSystemBars: Boolean = false, content: @Composable () -> Unit) {
    val revision by UserSettings.revision.collectAsState()
    val theme = remember(revision) { UserSettings.string("theme", "SYSTEM") }
    val dark =
        when (theme) {
            "DARK",
            "AMOLED" -> true
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
    val colors =
        if (dark)
            darkColorScheme(
                primary = lerp(accent, Color.White, .48f),
                onPrimary = Color(0xFF102019),
                primaryContainer = lerp(accent, Color.Black, .55f),
                onPrimaryContainer = lerp(accent, Color.White, .8f),
                secondary = lerp(accent, Color.White, .55f),
                onSecondary = Color(0xFF102019),
                secondaryContainer = lerp(accent, Color.Black, .65f),
                onSecondaryContainer = Color(0xFFE2EAE6),
                background = Color(0xFF111715),
                onBackground = Color(0xFFE4EAE6),
                surface = Color(0xFF111715),
                surfaceBright = Color(0xFF303B35),
                surfaceDim = Color(0xFF0D1210),
                inverseSurface = Color(0xFFE4EAE6),
                inverseOnSurface = Color(0xFF1C2822),
                inversePrimary = accent,
                surfaceContainerLowest = Color(0xFF0D1210),
                surfaceContainerLow = Color(0xFF19211E),
                surfaceContainer = Color(0xFF1D2622),
                surfaceContainerHigh = Color(0xFF26302B),
                surfaceContainerHighest = Color(0xFF303B35),
                onSurface = Color(0xFFE4EAE6),
                onSurfaceVariant = Color(0xFFBCC9C0),
                outline = Color(0xFF87958C),
                outlineVariant = Color(0xFF3B4941),
                tertiary = Color(0xFFE5BD82),
                onTertiary = Color(0xFF2B1900),
                tertiaryContainer = Color(0xFF554329),
                onTertiaryContainer = Color(0xFFFFDDB0),
                surfaceTint = Color.Transparent,
            )
        else
            lightColorScheme(
                primary = accent,
                onPrimary = Color.White,
                primaryContainer = lerp(accent, Color.White, .82f),
                onPrimaryContainer = lerp(accent, Color.Black, .5f),
                secondary = accent,
                onSecondary = Color.White,
                secondaryContainer = lerp(accent, Color.White, .88f),
                onSecondaryContainer = lerp(accent, Color.Black, .45f),
                background = Color(0xFFFAF9F5),
                onBackground = Color(0xFF1C2822),
                surface = Color(0xFFFAF9F5),
                surfaceBright = Color(0xFFFAF9F5),
                surfaceDim = Color(0xFFDDE3DA),
                inverseSurface = Color(0xFF1C2822),
                inverseOnSurface = Color(0xFFFAF9F5),
                inversePrimary = lerp(accent, Color.White, .48f),
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = Color(0xFFF1F3EE),
                surfaceContainer = Color(0xFFECEFE9),
                surfaceContainerHigh = Color(0xFFE6EBE3),
                surfaceContainerHighest = Color(0xFFE0E7DD),
                onSurface = Color(0xFF1C2822),
                onSurfaceVariant = Color(0xFF536258),
                outline = Color(0xFF75857A),
                outlineVariant = Color(0xFFCFD9CF),
                tertiary = Color(0xFF795A27),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFFFDEAC),
                onTertiaryContainer = Color(0xFF2B1900),
                surfaceTint = Color.Transparent,
            )
    val selectedColors =
        if (UserSettings.bool("dynamicColor", false) && android.os.Build.VERSION.SDK_INT >= 31) {
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
    val customScale = UserSettings.number("fontScale", 1f).coerceIn(.85f, 1.3f)
    val effectiveFontScale =
        if (density.fontScale >= 2f) density.fontScale
        else (density.fontScale * customScale).coerceAtMost(2f)
    val reducedMotion =
        UserSettings.bool("reduceMotion", false) ||
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
