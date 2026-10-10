package com.ttmrase.crownfall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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

/** ダイナミックカラー無し・常時ダークの量産型風テーマ（金・紺・赤）。 */
@Composable
fun CrownfallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrownfallColors, content = content)
}
