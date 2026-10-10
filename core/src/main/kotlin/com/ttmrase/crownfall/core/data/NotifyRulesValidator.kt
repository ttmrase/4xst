package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.model.RaidStage

/** notification_rules.json の整合検証（違反は [GameDataException]）。 */
internal object NotifyRulesValidator {
    val CATEGORIES = setOf("RAID", "TIMER", "ALLIANCE", "OFFER", "EVENT", "ENGAGEMENT", "COMEBACK")

    private fun fail(message: String): Nothing = throw GameDataException("notification_rules.json: $message")

    fun validate(data: GameData) {
        val r = data.notifyRules
        val stages = r.raid.stages
        if (stages.map { it.stage } != RaidStage.entries.map { it.name }) {
            fail(
                "raid stages must be ${RaidStage.entries}",
            )
        }
        if (stages.any { it.offsetSec < 0 || it.minWatchtowerLv < 0 }) fail("negative stage values")
        if (stages.zipWithNext().any { (a, b) -> a.offsetSec < b.offsetSec }) fail("stage offsets must not increase")
        if (stages.last().minWatchtowerLv != 0 || stages[stages.size - 2].minWatchtowerLv != 0) {
            fail("STARTED and RESULT must be visible at Watchtower Lv0")
        }
        if (r.intensity.keys != setOf("LOW", "STANDARD", "MASS")) fail("intensity must have LOW/STANDARD/MASS")
        for ((name, rule) in r.intensity) {
            if (rule.hourlyCap < 1) fail("$name: hourlyCap must be >= 1")
            if (rule.categories.any { it !in CATEGORIES }) fail("$name: unknown category")
            if ("RAID" !in rule.categories) fail("$name: RAID must always be enabled")
        }
        if (r.lookaheadHorizonSec <= 0 || r.offerLeadSec.isEmpty() || r.comebackDelaysSec.isEmpty()) {
            fail(
                "bad lead times",
            )
        }
        for (m in listOf(r.dailyReminderLocalMinute, r.loginBonusLocalMinute, r.freeWheelLocalMinute)) {
            if (m !in 0 until MINUTES_PER_DAY) fail("local minute out of range")
        }
    }

    private const val MINUTES_PER_DAY = 1440
}
