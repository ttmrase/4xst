package com.ttmrase.crownfall.core.util

/** 残り時間の書式（数値と記号のみ。単位の語は持たない）。 */
object DurationFormat {
    private const val MINUTE = 60L
    private const val HOUR = 3600L
    private const val DAY = 86_400L

    /** `m:ss` / `h:mm:ss` / `Nd hh:mm:ss`。負数は 0 として扱う。 */
    fun clock(totalSec: Long): String {
        val t = maxOf(0L, totalSec)
        val days = t / DAY
        val h = t % DAY / HOUR
        val m = t % HOUR / MINUTE
        val s = t % MINUTE
        return when {
            days > 0 -> "${days}d ${two(h)}:${two(m)}:${two(s)}"
            h > 0 -> "$h:${two(m)}:${two(s)}"
            else -> "$m:${two(s)}"
        }
    }

    private fun two(v: Long) = v.toString().padStart(2, '0')
}
