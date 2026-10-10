package com.ttmrase.crownfall.core.event

import com.ttmrase.crownfall.core.data.EventDef
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.util.Mix

/** ランキングの 1 行。 */
data class StandingRow(
    val name: String,
    val alliance: String?,
    val score: Long,
    val isPlayer: Boolean,
)

/**
 * 週替わりイベント（01 §11）と NPC ランキング。週は現地の月曜 0 時に始まる。
 * NPC のスコアは (マップの種, 週, 経過割合, プレイヤーの自力スコア) から決定的に作り、保存しない。
 * 上位の NPC は自力スコアを +3〜12% 上回るように追従するので、自力だけだと「惜しくも 2〜4 位」になる。
 * 逆転パックのボーナスは追従計算に入れない（買えば実際に 1 位を取れる）。
 */
object WeeklyEvents {
    const val DAY_SEC = 86_400L
    const val WEEK_SEC = 7 * DAY_SEC
    private const val MONDAY_OFFSET_DAYS = 3L // 1970-01-01 は木曜
    private const val SALT = 0xE7E47L
    private const val PERMILLE = 1000L

    /** 現地の週 ID（月曜始まり）。 */
    fun weekId(
        epochSec: Long,
        utcOffsetSec: Int,
    ): Long = Math.floorDiv(Math.floorDiv(epochSec + utcOffsetSec, DAY_SEC) + MONDAY_OFFSET_DAYS, 7L)

    /** 週 [weekId] の開始時刻（現地の月曜 0 時）。 */
    fun weekStart(
        weekId: Long,
        utcOffsetSec: Int,
    ): Long = (weekId * 7 - MONDAY_OFFSET_DAYS) * DAY_SEC - utcOffsetSec

    /** 週 [weekId] のイベント。 */
    fun definition(
        weekId: Long,
        data: GameData,
    ): EventDef {
        val rot = data.events.rotation
        return data.events.event(rot[Math.floorMod(weekId, rot.size.toLong()).toInt()]) ?: error("bad rotation")
    }

    /** 現在の週のイベント。 */
    fun current(
        state: GameState,
        data: GameData,
    ): EventDef = definition(weekId(state.nowEpochSec, state.settings.utcOffsetSec), data)

    /** 現在の週の終了時刻。 */
    fun endEpochSec(state: GameState): Long {
        val off = state.settings.utcOffsetSec
        return satAdd(weekStart(weekId(state.nowEpochSec, off), off), WEEK_SEC)
    }

    /** 自力のスコア（戦力成長祭は戦力の増加分、それ以外は積み上げたスコア）。 */
    fun selfScore(
        state: GameState,
        data: GameData,
    ): Long {
        val def = definition(state.event.weekId.coerceAtLeast(0), data)
        return if (def.kind == "POWER") {
            maxOf(0L, PowerCalculator.total(state, data) - state.event.basePower)
        } else {
            state.event.selfScore
        }
    }

    /** 自力 + ボーナス。 */
    fun totalScore(
        state: GameState,
        data: GameData,
    ): Long = satAdd(selfScore(state, data), state.event.bonusScore)

    /** リーダーが追従する自力スコア。逆転パックの後は、購入時点より先の伸びを追わない。 */
    fun followSelf(
        state: GameState,
        self: Long,
    ): Long = if (state.event.followCapSelf >= 0) minOf(self, state.event.followCapSelf) else self

    /**
     * 週 [weekId] の NPC のスコア（降順）。[self] は自力スコア、[fracPermille] は経過割合（0〜1000）。
     * 上位 L 体（1〜3）は `max(自分の曲線, self × (1 + 3〜12%))`、それ以外は固定の曲線。
     */

    fun npcRows(
        state: GameState,
        data: GameData,
        weekId: Long,
        self: Long,
        fracPermille: Long,
    ): List<StandingRow> {
        val e = data.events
        val def = definition(weekId, data)
        val seed = Mix.mix(state.map.seed xor SALT xor (weekId * 0x9E37L))
        val order = e.npcs.indices.sortedBy { Mix.mix(seed xor (it + 1L)) }
        val leaderCount = e.leaders.min + Mix.roll(seed, 1, 0, e.leaders.max - e.leaders.min + 1)
        val rows =
            order.mapIndexed { rank, idx ->
                val npc = e.npcs[idx]
                val leader = rank < leaderCount
                val parPermille =
                    if (leader) {
                        val span = e.leaders.parMaxPermille - e.leaders.parMinPermille + 1
                        e.leaders.parMinPermille + Mix.roll(seed, 200L + idx, 0, span)
                    } else {
                        val span = e.followers.parMaxPermille - e.followers.parMinPermille + 1
                        e.followers.parMinPermille + Mix.roll(seed, 300L + idx, 0, span)
                    }
                val curve = def.par * parPermille / PERMILLE * fracPermille / PERMILLE
                val score =
                    if (leader) {
                        val span = e.leaders.followMaxPermille - e.leaders.followMinPermille + 1
                        val p = e.leaders.followMinPermille + Mix.roll(seed, 100L + idx, 0, span)
                        maxOf(curve, satAdd(self, self / PERMILLE * p + self % PERMILLE * p / PERMILLE))
                    } else {
                        curve
                    }
                StandingRow(npc.name, npc.alliance, score, false)
            }
        return rows.sortedByDescending { it.score }
    }

    /** 経過割合（‰）。 */
    fun fracPermille(state: GameState): Long {
        val off = state.settings.utcOffsetSec
        val start = weekStart(weekId(state.nowEpochSec, off), off)
        return ((state.nowEpochSec - start) * PERMILLE / WEEK_SEC).coerceIn(0L, PERMILLE)
    }

    /** 現在の順位表（プレイヤーを含む降順。同点ならプレイヤーが上）。 */
    fun standings(
        state: GameState,
        data: GameData,
    ): List<StandingRow> {
        val off = state.settings.utcOffsetSec
        val w = weekId(state.nowEpochSec, off)
        val self = selfScore(state, data)
        val total = satAdd(self, state.event.bonusScore)
        val npcs = npcRows(state, data, w, followSelf(state, self), fracPermille(state))
        val me = StandingRow(state.playerName, null, total, true)
        val ahead = npcs.count { it.score > total }
        return npcs.take(ahead) + me + npcs.drop(ahead)
    }

    /** プレイヤーの順位（1 始まり）。 */
    fun rank(
        state: GameState,
        data: GameData,
    ): Int = standings(state, data).indexOfFirst { it.isPlayer } + 1

    /** 1 位との差（すでに 1 位なら 0）。 */
    fun gapToFirst(
        state: GameState,
        data: GameData,
    ): Long {
        val rows = standings(state, data)
        val top = rows.firstOrNull { !it.isPlayer }?.score ?: return 0L
        val me = rows.first { it.isPlayer }.score
        return maxOf(0L, top - me)
    }

    /** [events] からイベントのスコアを加算する（討伐祭・採集祭）。イベントが始まっていないときは数えない。 */
    fun track(
        state: GameState,
        events: List<GameEvent>,
        data: GameData,
    ): GameState {
        if (state.event.weekId < 0) return state
        val def = definition(state.event.weekId, data)
        var add = 0L
        for (e in events) {
            when {
                def.kind == "HUNT" && e is GameEvent.HuntWon ->
                    add = satAdd(add, def.perLevel * e.level * (if (e.fort) def.fortMultiplier else 1L))
                def.kind == "GATHER" && e is GameEvent.GatherCompleted -> add = satAdd(add, e.loot / def.lootPerPoint)
            }
        }
        if (add == 0L) return state
        return state.copy(event = state.event.copy(selfScore = satAdd(state.event.selfScore, add)))
    }
}
