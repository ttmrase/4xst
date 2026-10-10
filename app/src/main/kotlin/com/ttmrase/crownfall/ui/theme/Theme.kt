package com.ttmrase.crownfall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val CrownfallColors =
    darkColorScheme(
        primary = Gold,
        onPrimary = OnGold,
        secondary = Navy,
        onSecondary = OnDark,
        tertiary = Crimson,
        onTertiary = OnDark,
        background = Background,
        onBackground = OnDark,
        surface = Surface,
        onSurface = OnDark,
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
