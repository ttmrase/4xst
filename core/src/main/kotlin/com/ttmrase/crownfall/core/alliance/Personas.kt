package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PersonaDef

/** ペルソナの時間・城 Lv・呼び方の計算（純関数）。 */
object Personas {
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86_400L
    private const val HOURS_PER_DAY = 24

    /** 現地日（エポック日 + UTC オフセット）。 */
    fun localDay(
        epochSec: Long,
        utcOffsetSec: Int,
    ): Long = Math.floorDiv(epochSec + utcOffsetSec, SECONDS_PER_DAY)

    /** 現地時（0..23）。 */
    fun localHour(
        epochSec: Long,
        utcOffsetSec: Int,
    ): Int = Math.floorMod(Math.floorDiv(epochSec + utcOffsetSec, SECONDS_PER_HOUR), HOURS_PER_DAY.toLong()).toInt()

    /** 現地時刻 [epochSec] にペルソナが活動中か。 */
    fun isActive(
        def: PersonaDef,
        epochSec: Long,
        utcOffsetSec: Int,
    ): Boolean {
        val h = localHour(epochSec, utcOffsetSec)
        return def.activeHours.any { Math.floorMod(h - it.start, HOURS_PER_DAY) < it.end - it.start }
    }

    /** 活動中のペルソナ（定義順）。 */
    fun active(
        data: GameData,
        epochSec: Long,
        utcOffsetSec: Int,
    ): List<PersonaDef> = data.personas.personas.filter { isActive(it, epochSec, utcOffsetSec) }

    /** 現地時刻 [epochSec] がちょうど [def] の活動区間の開始時（毎時 0 分）か。 */
    fun startsAt(
        def: PersonaDef,
        epochSec: Long,
        utcOffsetSec: Int,
    ): Boolean {
        if (Math.floorMod(epochSec + utcOffsetSec, SECONDS_PER_HOUR) != 0L) return false
        val h = localHour(epochSec, utcOffsetSec)
        return def.activeHours.any { it.start % HOURS_PER_DAY == h }
    }

    /** 同盟開始から [days] 日後の城 Lv（castleLvCurve の折れ線補間、切り捨て）。 */
    fun castleLv(
        def: PersonaDef,
        days: Long,
    ): Int {
        val curve = def.castleLvCurve
        val d = days.coerceAtLeast(0)
        val next = curve.indexOfFirst { it[0] > d }
        if (next < 0) return curve.last()[1]
        val a = curve[next - 1]
        val b = curve[next]
        return (a[1] + (b[1] - a[1]) * (d - a[0]) / (b[0] - a[0])).toInt()
    }

    /** 好感度 [favor] のレベル（閾値を超えた数）。 */
    fun favorLevel(
        def: PersonaDef,
        favor: Int,
    ): Int = def.favorThresholds.count { favor >= it }

    /** 呼び方（レベル 0〜1: 領主さん、2: 名前、3 以上: あだ名）。 */
    fun callName(
        def: PersonaDef,
        favorLv: Int,
        playerName: String,
        defaultCall: String,
    ): String =
        when {
            playerName.isBlank() || favorLv < 2 -> defaultCall
            favorLv == 2 -> playerName
            else -> playerName + def.nicknameSuffix
        }
}
