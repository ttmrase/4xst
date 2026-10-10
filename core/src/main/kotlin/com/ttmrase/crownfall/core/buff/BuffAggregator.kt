package com.ttmrase.crownfall.core.buff

import com.ttmrase.crownfall.core.alliance.AllianceTech
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType

/** バフの集計結果（[BuffKey] → bp 合計）。 */
class BuffSet(private val bp: Map<BuffKey, Long>) {
    /** [key] の bp 合計（無ければ 0）。 */
    operator fun get(key: BuffKey): Long = bp[key] ?: 0L

    /** [extra] を加算した新しい [BuffSet]（英雄パッシブなど、行軍単位の追加分に使う）。 */
    operator fun plus(extra: Map<BuffKey, Long>): BuffSet {
        val merged = HashMap(bp)
        for ((k, v) in extra) merged[k] = (merged[k] ?: 0L) + v
        return BuffSet(merged)
    }

    /** 資源 [type] の生産に効く bp（全体用 + 個別用）。 */
    fun production(type: ResourceType): Long =
        this[BuffKey.PRODUCTION_ALL] +
            this[
                when (type) {
                    ResourceType.FOOD -> BuffKey.PRODUCTION_FOOD
                    ResourceType.WOOD -> BuffKey.PRODUCTION_WOOD
                    ResourceType.STONE -> BuffKey.PRODUCTION_STONE
                    ResourceType.GOLD -> BuffKey.PRODUCTION_GOLD
                },
            ]

    override fun equals(other: Any?): Boolean = other is BuffSet && other.bp == bp

    override fun hashCode(): Int = bp.hashCode()

    override fun toString(): String = "BuffSet($bp)"
}

/** 英雄パッシブスキルの効果キーと [BuffKey] の対応。 */
object HeroPassives {
    /** 効果キー（heroes.json）に対応するバフ。無ければ null。 */
    fun buffKey(effectKey: String): BuffKey? =
        when (effectKey) {
            "troop_attack_bp" -> BuffKey.TROOP_ATTACK
            "troop_defense_bp" -> BuffKey.TROOP_DEFENSE
            "troop_hp_bp" -> BuffKey.TROOP_HP
            "gather_speed_bp" -> BuffKey.GATHER_SPEED
            "march_speed_bp" -> BuffKey.MARCH_SPEED
            "load_bp" -> BuffKey.LOAD_CAPACITY
            else -> null
        }
}

/**
 * バフの集約。同じ量に効くバフは出所（研究・Boost）と粒度（全体用・個別用）を問わず bp で**加算**し、
 * 最後に 1 回だけ掛ける（`value × (10000 + Σbp) / 10000`）。乗算の連鎖はしない。
 * 英雄スキルは M3 では含めない（行軍・戦闘の文脈でだけ効かせる。M4）。
 */
object BuffAggregator {
    /** [state] の Boost と研究から [BuffSet] を作る。 */
    fun compute(
        state: GameState,
        data: GameData,
    ): BuffSet {
        val sum = HashMap<BuffKey, Long>()

        fun add(
            key: BuffKey,
            bp: Long,
        ) {
            sum[key] = (sum[key] ?: 0L) + bp
        }
        for (boost in state.boosts) {
            when (boost.kind) {
                BoostKind.PRODUCTION -> add(BuffKey.PRODUCTION_ALL, boost.bp.toLong())
                BoostKind.BUILD_SPEED -> add(BuffKey.BUILD_SPEED, boost.bp.toLong())
            }
        }
        for ((nodeId, level) in state.research) {
            val node = data.research[nodeId] ?: continue
            if (level <= 0) continue
            val l = node.level(minOf(level, node.maxLevel))
            add(l.effectKey, l.effectBp)
        }
        for ((key, bp) in AllianceTech.buffs(state, data)) add(key, bp)
        return BuffSet(sum)
    }

    /**
     * 行軍（戦闘・採集）用のバフ = 通常の集約 + 参加英雄（[heroIds]）のパッシブスキル。
     * スキルは Lv に応じた値（bp）を加算する。アクティブスキルはここには含めない（戦闘ラウンドで扱う）。
     */
    fun forMarch(
        state: GameState,
        heroIds: List<String>,
        data: GameData,
    ): BuffSet {
        val extra = HashMap<BuffKey, Long>()
        for (id in heroIds) {
            val hero = state.heroes.firstOrNull { it.heroId == id } ?: continue
            val def = data.heroes[id] ?: continue
            def.skills.forEachIndexed { index, skill ->
                if (skill.slot != "passive") return@forEachIndexed
                val key = HeroPassives.buffKey(skill.effectKey) ?: return@forEachIndexed
                val level = hero.skillLevels.getOrElse(index) { 1 }.coerceIn(1, skill.values.size)
                extra[key] = (extra[key] ?: 0L) + skill.values[level - 1]
            }
        }
        return compute(state, data) + extra
    }
}
