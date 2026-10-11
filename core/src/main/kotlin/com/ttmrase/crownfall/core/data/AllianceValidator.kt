package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.buff.BuffKey

/** personas.json / alliance_rules.json / templates.json の整合検証（違反は [GameDataException]）。 */
internal object AllianceValidator {
    private const val PERSONA_COUNT = 8
    private const val FILLER_COUNT = 22

    /** ポートレート（pt_gen_<look>_<nn>）の生成枚数。tools/artgen/gen_portraits.py の GENERIC_LOOKS と揃える。 */
    private val FILLER_LOOKS = mapOf("m" to 8, "f" to 8, "n" to 6)
    private const val HOURS_PER_DAY = 24
    private const val MAX_HOUR = 48
    private const val PERMILLE = 1000
    private const val MAX_FAVOR_LEVELS = 8

    private fun fail(message: String): Nothing = throw GameDataException(message)

    fun validate(data: GameData) {
        validatePersonas(data.personas)
        validateRules(data)
        validateQuizzes(data)
    }

    private fun validHours(h: HourRange): Boolean =
        h.start in 0 until HOURS_PER_DAY && h.end in 1..MAX_HOUR && h.end > h.start && h.end - h.start <= HOURS_PER_DAY

    private fun validatePersonas(p: PersonasFile) {
        if (p.personas.size != PERSONA_COUNT) fail("personas.json: expected $PERSONA_COUNT personas")
        if (p.personas.map { it.id }.toSet().size != p.personas.size) fail("personas.json: duplicate persona id")
        if (p.fillerMembers.size != FILLER_COUNT) fail("personas.json: expected $FILLER_COUNT filler members")
        val perLook = p.fillerMembers.groupingBy { it.look }.eachCount()
        if (perLook.any { (look, n) -> n > (FILLER_LOOKS[look] ?: -1) }) {
            fail("personas.json: filler look must be m/f/n and within the generated portrait counts $FILLER_LOOKS")
        }
        if (p.worldNpcNames.isEmpty()) fail("personas.json: worldNpcNames is empty")
        for (d in p.personas) {
            if (d.rank !in 1..5) fail("personas.json: ${d.id} rank must be 1..5")
            if (d.activeHours.isEmpty() || d.activeHours.any { !validHours(it) }) {
                fail("personas.json: ${d.id} activeHours are invalid")
            }
            val axes = with(d.personality) { listOf(extraversion, caring, aggression, meticulous, humor) }
            if (axes.any { it !in 0..PERMILLE }) fail("personas.json: ${d.id} personality must be 0..1000")
            val style = d.speechStyle
            if (style.samples.size < 5 || style.endings.isEmpty() || style.emojiPermille !in 0..PERMILLE) {
                fail("personas.json: ${d.id} speechStyle is invalid")
            }
            val curve = d.castleLvCurve
            if (curve.isEmpty() || curve.any { it.size != 2 } ||
                curve.zipWithNext().any {
                        (a, b) ->
                    b[0] <= a[0]
                } || curve.first()[0] != 0
            ) {
                fail("personas.json: ${d.id} castleLvCurve must start at day 0 with increasing days")
            }
            val th = d.favorThresholds
            if (th.isEmpty() || th.size > MAX_FAVOR_LEVELS ||
                th.zipWithNext().any {
                        (a, b) ->
                    b <= a
                } || th.first() <= 0
            ) {
                fail("personas.json: ${d.id} favorThresholds must be positive and increasing")
            }
            if (d.troopTier !in 1..4) fail("personas.json: ${d.id} troopTier must be 1..4")
        }
    }

    private fun validateRules(data: GameData) {
        val r = data.allianceRules
        val ids = data.personas.personas.map { it.id }.toSet()
        if (r.schedulerPeriodSec != 60L) fail("alliance_rules.json: schedulerPeriodSec must be 60")
        val caps = with(r.caps) { listOf(pendingChat, history, actionLog, openRequests, openRallies, helpPool, gifts) }
        if (caps.any { it <= 0 }) fail("alliance_rules.json: caps must be positive")
        val chances =
            with(r.chancePermillePerMinute) {
                listOf(
                    ambient, worldAmbient, needsHelp, asksScout, purchase, johnnyPurchase,
                    aiRally, memberHelpRequest, quiz, yukiReturn, aiHelpOnPlayerTimer,
                )
            }
        if (chances.any { it !in 0..PERMILLE }) fail("alliance_rules.json: chances must be 0..1000")
        if (r.techs.isEmpty() || r.techs.map { it.id }.toSet().size != r.techs.size) {
            fail(
                "alliance_rules.json: techs invalid",
            )
        }
        for (t in r.techs) {
            if (t.expPerLevel.isEmpty() || t.expPerLevel.size != t.bpPerLevel.size) {
                fail(
                    "alliance_rules.json: tech ${t.id} tables differ",
                )
            }
            if (t.expPerLevel.zipWithNext().any {
                        (a, b) ->
                    b <= a
                } || t.expPerLevel.first() <= 0
            ) {
                fail("alliance_rules.json: tech ${t.id} exp must increase")
            }
            if (BuffKey.entries.none { it.name == t.buff }) {
                fail(
                    "alliance_rules.json: tech ${t.id} unknown buff ${t.buff}",
                )
            }
        }
        if (r.shop.map { it.id }.toSet().size != r.shop.size) fail("alliance_rules.json: duplicate shop id")
        for (e in r.shop) {
            if ((e.item == null) == (e.resources == null)) {
                fail(
                    "alliance_rules.json: shop ${e.id} needs item or resources",
                )
            }
            if (e.item != null && e.item !in data.items) {
                fail(
                    "alliance_rules.json: shop ${e.id} unknown item ${e.item}",
                )
            }
            if (e.cost <= 0 || e.dailyLimit <= 0 || e.count <= 0) {
                fail(
                    "alliance_rules.json: shop ${e.id} numbers invalid",
                )
            }
        }
        if (r.gifts.isEmpty() ||
            r.gifts.any {
                it.weight <= 0 || (it.item != null && it.item !in data.items)
            }
        ) {
            fail("alliance_rules.json: gifts invalid")
        }
        if (r.rally.hosts.isEmpty() ||
            r.rally.hosts.any {
                it !in ids
            }
        ) {
            fail("alliance_rules.json: rally hosts must be persona ids")
        }
        if (r.quizzes.isEmpty()) fail("alliance_rules.json: quizzes is empty")
        val mix = r.rally.allyMix
        if (mix.values.sum() != PERCENT_MAX ||
            mix.keys.any {
                    k ->
                TroopType.entries.none { it.name.lowercase() == k }
            }
        ) {
            fail("alliance_rules.json: rally.allyMix must sum to 100 over troop types")
        }
        if (r.help.dailyLimit <= 0 || r.help.reductionPct !in 1..PERCENT_MAX || r.help.favorEveryHelps <= 0) {
            fail(
                "alliance_rules.json: help invalid",
            )
        }
        if (r.donate.resourceUnit <= 0 || r.donate.gemUnit <= 0 || r.donate.dailyLimit <= 0) {
            fail(
                "alliance_rules.json: donate invalid",
            )
        }
    }

    private fun validateQuizzes(data: GameData) {
        for (q in data.allianceRules.quizzes) {
            val text = data.templates.quizzes[q.id] ?: fail("templates.json: missing quiz text ${q.id}")
            if (text.options.size != 3 || q.correct !in text.options.indices) {
                fail(
                    "templates.json: quiz ${q.id} must have 3 options",
                )
            }
        }
    }

    private const val PERCENT_MAX = 100
}
