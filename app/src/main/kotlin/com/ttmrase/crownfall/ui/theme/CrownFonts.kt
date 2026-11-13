package com.ttmrase.crownfall.ui.theme

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.ttmrase.crownfall.R
import androidx.compose.ui.text.googlefonts.Font as GoogleFontEntry

/**
 * 見出しと数字のフォント（docs/ASSETS.md）。どちらも SIL OFL 1.1。
 * - [Display]: Noto Serif JP（和文の見出し。英数字も含む）。Google Fonts のダウンロード方式（APK には同梱しない）。
 *   端末で取得できないとき（オフライン・Play 開発者サービスなし）は、後ろの端末の serif に落ちる。
 * - [Numeric]: Cinzel（数字・「OFF」などの欧文・題字）。res/font に同梱（2 ウェイト、計 92 KB）。
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
    ) = GoogleFontEntry(GoogleFont(name), provider, weight)

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

    /**
     * 数字・欧文（価格・割引・カウントダウン・題字）。Cinzel を同梱する（OFL。オフラインでも同じ見た目）。
     * 和文は Cinzel に無いので、文字ごとに端末のフォントへ落ちる。
     */
    val Numeric: FontFamily =
        FontFamily(
            Font(R.font.cinzel_bold, FontWeight.Bold),
            Font(R.font.cinzel_bold, FontWeight.SemiBold),
            Font(R.font.cinzel_black, FontWeight.Black),
        )
}
