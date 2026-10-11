package com.ttmrase.crownfall.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VIP_ON_DARK = Color(0xFFFFD27A)
private val VIP_ON_LIGHT = Color(0xFF7A4E00)

/** VIP の文字色。暗い面でも明るい面でも 4.5:1 以上のコントラストになる金色（テーマの tertiary は暗い面で沈むことがある）。 */
@Composable
fun vipTextColor(): Color = if (isSystemInDarkTheme()) VIP_ON_DARK else VIP_ON_LIGHT

/** 称号など、補足の文字色（面の上の文字色に近い、読める色）。 */
@Composable
fun noteTextColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant
