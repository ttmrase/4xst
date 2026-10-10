package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.model.BuildingType

/** quests.json / events.json の整合検証（違反は [GameDataException]）。 */
internal object QuestValidator {
    private const val CHAPTERS = 20
    private const val DAILIES = 10
    private const val CHESTS = 5
    private const val PERMILLE = 1000
    private val KINDS = setOf("building", "power", "research", "heroes", "counter")
    private val EVENT_KINDS = setOf("HUNT", "GATHER", "POWER")

    private fun fail(message: String): Nothing = throw GameDataException(message)

    fun validate(data: GameData) {
        validateQuests(data)
        validateEvents(data)
    }

    private fun validateCond(
        where: String,
        c: QuestCond,
    ) {
        if (c.type !in KINDS) fail("$where: unknown condition ${c.type}")
        when (c.type) {
            "building" ->
                if (BuildingType.entries.none { it.name.lowercase() == c.building } || c.lv < 1) {
                    fail("$where: bad building condition")
                }
            "power" -> if (c.value <= 0) fail("$where: power needs value")
            "research" -> if (c.total <= 0) fail("$where: research needs total")
            "heroes" -> if (c.count <= 0) fail("$where: heroes needs count")
            "counter" -> if (c.key.isNullOrBlank() || c.target <= 0) fail("$where: counter needs key and target")
        }
    }

    private fun validateReward(
        where: String,
        r: PackContents,
        data: GameData,
    ) {
        if (r.isEmpty || r.gems < 0) fail("$where: reward must be non-empty")
        for ((id, n) in r.items) if (id !in data.items || n <= 0) fail("$where: bad reward item $id")
    }

    private fun validateQuests(data: GameData) {
        val q = data.quests
        if (q.chapters.size != CHAPTERS) fail("quests.json: expected $CHAPTERS chapters")
        if (q.dailies.size != DAILIES) fail("quests.json: expected $DAILIES dailies")
        if (q.chests.size != CHESTS) fail("quests.json: expected $CHESTS chests")
        val ids =
            q.chapters.map { it.id } + q.chapters.flatMap { c -> c.tasks.map { it.id } } +
                q.dailies.map { it.id } + q.alliance.map { it.id } + q.achievements.map { it.id }
        if (ids.toSet().size != ids.size) fail("quests.json: duplicate id")
        for (c in q.chapters) {
            if (c.tasks.size !in 3..5) fail("quests.json: ${c.id} needs 3..5 tasks")
            c.tasks.forEach { validateCond("quests.json: ${it.id}", it.cond) }
            validateReward("quests.json: ${c.id}", c.reward, data)
        }
        for (d in q.dailies) if (d.target <= 0 || d.points <= 0 || d.key.isBlank()) fail("quests.json: daily ${d.id}")
        val maxPoints = q.dailies.sumOf { it.points }
        if (q.chests.map { it.tier } != (1..CHESTS).toList() ||
            q.chests.zipWithNext().any { (a, b) -> b.points <= a.points } || q.chests.last().points > maxPoints
        ) {
            fail("quests.json: chests must be increasing and reachable")
        }
        q.chests.forEach { validateReward("quests.json: chest ${it.tier}", it.reward, data) }
        for (d in q.alliance + q.achievements) {
            validateCond("quests.json: ${d.id}", d.cond)
            validateReward("quests.json: ${d.id}", d.reward, data)
        }
    }

    private fun validateEvents(data: GameData) {
        val e = data.events
        if (e.rotation.isEmpty() || e.rotation.any { e.event(it) == null }) fail("events.json: bad rotation")
        for (d in e.events) {
            if (d.kind !in EVENT_KINDS || d.par <= 0) fail("events.json: event ${d.id}")
            if (d.kind == "HUNT" && d.perLevel <= 0) fail("events.json: ${d.id} needs perLevel")
            if (d.kind == "GATHER" && d.lootPerPoint <= 0) fail("events.json: ${d.id} needs lootPerPoint")
        }
        if (e.npcs.size < 6 || e.npcs.map { it.name }.toSet().size != e.npcs.size) fail("events.json: npcs")
        val l = e.leaders
        if (l.min < 1 || l.max < l.min || l.max >= e.npcs.size || l.followMinPermille <= 0 ||
            l.followMaxPermille < l.followMinPermille || l.parMinPermille <= 0 || l.parMaxPermille < l.parMinPermille
        ) {
            fail("events.json: leaders")
        }
        val f = e.followers
        if (f.parMinPermille <= 0 || f.parMaxPermille < f.parMinPermille || f.parMaxPermille >= l.parMinPermille) {
            fail("events.json: followers must trail the leaders")
        }
        if (e.warnBeforeEndSec <= 0 || e.bonusMarginPermille !in 0..PERMILLE) fail("events.json: warn/margin")
        val ranks = e.npcs.size + 1
        val covered = (1..ranks).all { r -> e.rewards.count { r in it.from..it.to } == 1 }
        if (!covered) fail("events.json: rewards must cover every rank exactly once")
        e.rewards.forEach { validateReward("events.json: rewards ${it.from}", it.reward, data) }
    }
}
