package com.ttmrase.crownfall.ui.theme

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.ttmrase.crownfall.R

/**
 * 見出しと数字のフォント（docs/ASSETS.md）。Google Fonts のダウンロード方式（SIL OFL 1.1、APK には同梱しない）。
 * 端末で取得できないとき（オフライン・Play 開発者サービスなし）は、各 FontFamily の後ろの端末の serif に落ちる。
 * - [Display]: Noto Serif JP（和文の見出し。英数字も含む）
 * - [Numeric]: Cinzel（数字・「OFF」などの欧文。和文は端末のフォントに落ちる）
 */
object CrownFonts {
    private val provider =
        GoogleFont.Provider(
            providerAuthority = "com.google.android.gms.fonts",
            providerPackage = "com.google.android.gms",
            certificates = R.array.com_google_android_gms_fonts_certs,
        )

    private fun google(
        name: String,
        weight: FontWeight,
    ) = Font(GoogleFont(name), provider, weight)

    private fun fallback(weight: FontWeight) = Font(DeviceFontFamilyName("serif"), weight)

    /** 和文の見出し。 */
    val Display: FontFamily =
        FontFamily(
            google("Noto Serif JP", FontWeight.Bold),
            google("Noto Serif JP", FontWeight.SemiBold),
            google("Noto Serif JP", FontWeight.Black),
            fallback(FontWeight.Bold),
            fallback(FontWeight.SemiBold),
            fallback(FontWeight.Black),
        )

    /** 数字・欧文（価格・割引・カウントダウン）。 */
    val Numeric: FontFamily =
        FontFamily(
            google("Cinzel", FontWeight.Bold),
            google("Cinzel", FontWeight.Black),
            google("Cinzel", FontWeight.SemiBold),
            fallback(FontWeight.Bold),
            fallback(FontWeight.Black),
            fallback(FontWeight.SemiBold),
        )
}
