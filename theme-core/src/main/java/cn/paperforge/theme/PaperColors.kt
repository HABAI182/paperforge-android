package cn.paperforge.theme

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

val paperAccents =
    linkedMapOf(
        "GREEN" to ("墨绿" to Color(0xFF176B54)),
        "BLUE" to ("海蓝" to Color(0xFF225BAA)),
        "PURPLE" to ("鸢紫" to Color(0xFF7250A0)),
        "ORANGE" to ("琥珀" to Color(0xFFA54B15)),
        "TEAL" to ("青碧" to Color(0xFF006B70)),
        "ROSE" to ("玫瑰" to Color(0xFFA23860)),
        "SLATE" to ("岩灰" to Color(0xFF475569)),
        "SEPIA" to ("咖啡" to Color(0xFF805A39)),
    )

/**
 * Shared by document, reader, update and connection screens. System mode only chooses light/dark.
 */
fun paperColorScheme(accent: String, dark: Boolean, theme: String): ColorScheme {
    val seed =
        when (theme) {
            "SEPIA" -> paperAccents.getValue("SEPIA").second
            "MIDNIGHT" -> Color(0xFF415F9B)
            else -> (paperAccents[accent] ?: paperAccents.getValue("GREEN")).second
        }
    val base =
        when (theme) {
            "SEPIA" -> Color(0xFFFCF5E8)
            "MIDNIGHT" -> Color(0xFF101826)
            "AMOLED" -> Color.Black
            else -> if (dark) Color(0xFF121416) else Color(0xFFFAFAF8)
        }
    fun tinted(amount: Float) = lerp(base, seed, amount)
    val ink = if (dark) Color(0xFFE7E9EC) else Color(0xFF202528)
    return if (dark)
        darkColorScheme(
            primary = lerp(seed, Color.White, .57f),
            onPrimary = Color(0xFF101A20),
            primaryContainer = lerp(seed, Color.Black, .60f),
            onPrimaryContainer = lerp(seed, Color.White, .85f),
            secondary = lerp(seed, Color.White, .64f),
            onSecondary = Color(0xFF101A20),
            secondaryContainer = lerp(seed, Color.Black, .68f),
            onSecondaryContainer = lerp(seed, Color.White, .87f),
            background = base,
            onBackground = ink,
            surface = base,
            onSurface = ink,
            surfaceBright = lerp(tinted(.07f), Color.White, .12f),
            surfaceDim = base,
            surfaceContainerLowest = base,
            surfaceContainerLow = lerp(tinted(.04f), Color.White, .025f),
            surfaceContainer = lerp(tinted(.06f), Color.White, .045f),
            surfaceContainerHigh = lerp(tinted(.08f), Color.White, .075f),
            surfaceContainerHighest = lerp(tinted(.10f), Color.White, .11f),
            onSurfaceVariant = Color(0xFFC1C6CC),
            outline = Color(0xFF8C959E),
            outlineVariant = Color(0xFF3D454D),
            inverseSurface = Color(0xFFE7E9EC),
            inverseOnSurface = Color(0xFF202528),
            inversePrimary = seed,
            surfaceTint = Color.Transparent,
        )
    else
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = lerp(seed, base, .86f),
            onPrimaryContainer = lerp(seed, Color.Black, .45f),
            secondary = seed,
            onSecondary = Color.White,
            secondaryContainer = lerp(seed, base, .91f),
            onSecondaryContainer = lerp(seed, Color.Black, .45f),
            background = base,
            onBackground = ink,
            surface = base,
            onSurface = ink,
            surfaceBright = base,
            surfaceDim = tinted(.12f),
            surfaceContainerLowest = if (theme == "SEPIA") Color(0xFFFFFBF4) else Color.White,
            surfaceContainerLow = tinted(.035f),
            surfaceContainer = tinted(.06f),
            surfaceContainerHigh = tinted(.09f),
            surfaceContainerHighest = tinted(.12f),
            onSurfaceVariant = Color(0xFF555F68),
            outline = Color(0xFF7A858F),
            outlineVariant = tinted(.20f),
            inverseSurface = Color(0xFF202528),
            inverseOnSurface = Color(0xFFFAFAF8),
            inversePrimary = lerp(seed, Color.White, .57f),
            surfaceTint = Color.Transparent,
        )
}
