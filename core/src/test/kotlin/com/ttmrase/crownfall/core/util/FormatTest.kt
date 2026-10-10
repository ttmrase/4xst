package com.ttmrase.crownfall.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FormatTest {
    @Test
    fun shortNumberBoundaries() {
        assertEquals("0", ShortNumber.format(0))
        assertEquals("999", ShortNumber.format(999))
        assertEquals("1.0K", ShortNumber.format(1_000))
        assertEquals("1.2K", ShortNumber.format(1_234))
        assertEquals("1.2K", ShortNumber.format(1_299)) // 切り捨て
        assertEquals("999.9K", ShortNumber.format(999_999))
        assertEquals("1.0M", ShortNumber.format(1_000_000))
        assertEquals("1.2M", ShortNumber.format(1_250_000))
        assertEquals("12.5B", ShortNumber.format(12_500_000_000))
        assertEquals("3.0T", ShortNumber.format(3_000_000_000_000))
        assertEquals("9223372.0T", ShortNumber.format(Long.MAX_VALUE))
        assertEquals("-1.2K", ShortNumber.format(-1_234))
        assertEquals("-5", ShortNumber.format(-5))
        assertEquals("-9223372.0T", ShortNumber.format(Long.MIN_VALUE))
    }

    @Test
    fun durationClock() {
        assertEquals("0:00", DurationFormat.clock(0))
        assertEquals("0:00", DurationFormat.clock(-9))
        assertEquals("0:59", DurationFormat.clock(59))
        assertEquals("1:05", DurationFormat.clock(65))
        assertEquals("1:00:00", DurationFormat.clock(3600))
        assertEquals("2:03:04", DurationFormat.clock(2 * 3600 + 3 * 60 + 4))
        assertEquals("1d 00:00:00", DurationFormat.clock(86_400))
        assertEquals("7d 00:00:01", DurationFormat.clock(7 * 86_400 + 1))
    }

    @Test
    fun playerNameValidation() {
        assertEquals(PlayerNameResult.Valid("Alice"), PlayerName.validate("  Alice \n"))
        assertEquals(PlayerNameResult.Empty, PlayerName.validate(""))
        assertEquals(PlayerNameResult.Empty, PlayerName.validate("   "))
        assertEquals(PlayerNameResult.Valid("a".repeat(12)), PlayerName.validate("a".repeat(12)))
        assertEquals(PlayerNameResult.TooLong, PlayerName.validate("a".repeat(13)))
        assertEquals(PlayerNameResult.Valid("あ".repeat(12)), PlayerName.validate("あ".repeat(12)))
        assertEquals(PlayerNameResult.TooLong, PlayerName.validate("あ".repeat(13)))
        // サロゲートペアは 1 文字として数える
        assertEquals(PlayerNameResult.Valid("😀".repeat(12)), PlayerName.validate("😀".repeat(12)))
        assertEquals(PlayerNameResult.InvalidChars, PlayerName.validate("ab\u0007c"))
        assertIs<PlayerNameResult.Valid>(PlayerName.validate("Lord Kei"))
    }
}
