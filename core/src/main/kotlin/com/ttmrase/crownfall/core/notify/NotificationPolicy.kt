package com.ttmrase.crownfall.core.notify

import com.ttmrase.crownfall.core.data.NotificationRules

/** 通知の強度。 */
enum class Intensity { LOW, STANDARD, MASS }

/**
 * 通知の設定（端末側。セーブには入らない）。[disabled] はユーザーが切ったカテゴリ。おやすみ時間は現地の分（0〜1439）で、
 * [quietStartMinute] > [quietEndMinute] なら日をまたぐ。
 */
data class NotifySettings(
    val intensity: Intensity = Intensity.MASS,
    val disabled: Set<NoticeCategory> = emptySet(),
    val quietEnabled: Boolean = false,
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 7 * 60,
    val raidThroughQuiet: Boolean = true,
    val utcOffsetSec: Int = 0,
)

/**
 * 通知の選別（純関数）。強度のカテゴリ、ユーザーが切ったカテゴリ、おやすみ時間、1 時間あたりの上限を適用する。
 * 襲撃は上限の対象外。上限を超えた分は、その時間帯の最初の超過の時刻に「まとめ通知」1 件に畳む。
 */
object NotificationPolicy {
    private const val HOUR = 3600L
    private const val DAY = 86_400L
    private const val MINUTE = 60L

    fun apply(
        plan: List<PlannedNotice>,
        settings: NotifySettings,
        rules: NotificationRules,
    ): List<PlannedNotice> {
        val rule = rules.intensity.getValue(settings.intensity.name)
        val allowed = rule.categories.toSet() - settings.disabled.map { it.name }.toSet()
        val kept =
            plan.sortedWith(compareBy({ it.epochSec }, { it.type.ordinal }, { it.param }))
                .filter { it.type.category == NoticeCategory.SUMMARY || it.type.category.name in allowed }
                .filter { !inQuiet(it, settings) }
        val out = ArrayList<PlannedNotice>()
        val perHour = HashMap<Long, Int>()
        val folded = HashMap<Long, Pair<Long, Int>>() // 時間帯 → (最初の超過の時刻, 件数)
        for (n in kept) {
            if (n.type.category == NoticeCategory.RAID || n.type.category == NoticeCategory.SUMMARY) {
                out.add(n)
                continue
            }
            val bucket = Math.floorDiv(n.epochSec, HOUR)
            val used = perHour.getOrDefault(bucket, 0)
            if (used < rule.hourlyCap) {
                perHour[bucket] = used + 1
                out.add(n)
            } else {
                val prev = folded[bucket]
                folded[bucket] = (prev?.first ?: n.epochSec) to ((prev?.second ?: 0) + n.count)
            }
        }
        for ((_, f) in folded) {
            out.add(PlannedNotice(f.first, NoticeType.SUMMARY, NoticeType.SUMMARY.channel, f.second))
        }
        return out.sortedWith(compareBy({ it.epochSec }, { it.type.ordinal }, { it.param }))
    }

    private fun inQuiet(
        n: PlannedNotice,
        s: NotifySettings,
    ): Boolean {
        if (!s.quietEnabled) return false
        if (n.type.category == NoticeCategory.RAID && s.raidThroughQuiet) return false
        val minute = Math.floorMod(n.epochSec + s.utcOffsetSec, DAY) / MINUTE
        return if (s.quietStartMinute <= s.quietEndMinute) {
            minute in s.quietStartMinute until s.quietEndMinute
        } else {
            minute >= s.quietStartMinute || minute < s.quietEndMinute
        }
    }
}
