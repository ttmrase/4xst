package com.ttmrase.crownfall.core.util

/** 数値の短縮表示（01 §16）。1,000 未満はそのまま、K/M/B/T で小数 1 桁（切り捨て）。 */
object ShortNumber {
    private val units = listOf("K" to 1_000L, "M" to 1_000_000L, "B" to 1_000_000_000L, "T" to 1_000_000_000_000L)

    /** [value] を短縮表記にする。例: 1234 → "1.2K"、1_250_000 → "1.2M"、999 → "999"。 */
    fun format(value: Long): String {
        if (value < 0) return if (value == Long.MIN_VALUE) "-9223372.0T" else "-" + format(-value)
        if (value < 1_000) return value.toString()
        val (suffix, unit) = units.last { value >= it.second }
        val whole = value / unit
        val tenth = (value % unit) * 10 / unit
        return "$whole.$tenth$suffix"
    }
}
