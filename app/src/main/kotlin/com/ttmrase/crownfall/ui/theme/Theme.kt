package com.ttmrase.crownfall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.ui.kit.Cf

private val CrownfallColors =
    darkColorScheme(
        primary = Gold,
        onPrimary = OnGold,
        primaryContainer = Color(0xFF4A3608),
        onPrimaryContainer = Cf.GoldHi,
        secondary = Cf.Sapphire,
        onSecondary = Color.White,
        secondaryContainer = Cf.Night4,
        onSecondaryContainer = Cf.Ivory,
        tertiary = Crimson,
        onTertiary = OnDark,
        background = Cf.Night1,
        onBackground = Cf.Ivory,
        surface = Cf.Night2,
        onSurface = Cf.Ivory,
        surfaceVariant = Cf.Night3,
        onSurfaceVariant = Cf.Muted,
        surfaceContainerLowest = Cf.Night0,
        surfaceContainerLow = Cf.Night1,
        surfaceContainer = Cf.Night2,
        surfaceContainerHigh = Cf.Night3,
        surfaceContainerHighest = Cf.Night4,
        inverseSurface = Cf.Ivory,
        inverseOnSurface = Cf.Night1,
        outline = Cf.Line,
        outlineVariant = Color(0xFF2A3760),
        error = Color(0xFFFF7A6E),
        onError = Color(0xFF3A0510),
        scrim = Cf.Ink,
    )

/** 見出しだけ Display（Noto Serif JP）にする。本文は端末の既定のまま（読みやすさ優先）。 */
private val CrownfallTypography =
    Typography().let { base ->
        fun heading(style: TextStyle) = style.copy(fontFamily = CrownFonts.Display, fontWeight = FontWeight.Bold)
        base.copy(
            displayLarge = heading(base.displayLarge),
            displayMedium = heading(base.displayMedium),
            displaySmall = heading(base.displaySmall),
            headlineLarge = heading(base.headlineLarge),
            headlineMedium = heading(base.headlineMedium),
            headlineSmall = heading(base.headlineSmall),
            titleLarge = heading(base.titleLarge),
            titleMedium = heading(base.titleMedium).copy(fontSize = 17.sp),
        )
    }

/** ダイナミックカラー無し・常時ダークの量産型風テーマ（金・紺・赤）。 */
@Composable
fun CrownfallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrownfallColors, typography = CrownfallTypography, content = content)
}
