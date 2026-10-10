package com.ttmrase.crownfall.core.model

import kotlinx.serialization.Serializable

/**
 * ゲーム全体の状態（不変）。時刻は [nowEpochSec]（シミュレーション時刻）で持つ。
 * 生産の端数は [productionRemainder]（資源ごと 0..3599）に繰り越す。
 */
@Serializable
data class GameState(
    val nowEpochSec: Long,
    val resources: Resources,
    val gems: Long = 0,
    val productionRemainder: Resources = Resources.ZERO,
    val slots: List<BuildingSlot>,
    val timers: List<TimerJob> = emptyList(),
    val items: Map<String, Int> = emptyMap(),
    val boosts: List<Boost> = emptyList(),
    val secondBuilderUntilEpochSec: Long = 0,
    val nextJobId: Long = 1,
    val rngState: Long = 0,
    val storageFullFlags: Set<ResourceType> = emptySet(),
    val playerName: String = "",
    val starterHeroId: String? = null,
    val tutorialStep: Int = 0,
    val troops: Map<String, Long> = emptyMap(),
    val wounded: Map<String, Long> = emptyMap(),
    val research: Map<String, Int> = emptyMap(),
    val heroes: List<HeroState> = emptyList(),
    val summonPity: Int = 0,
    val heroShards: Map<String, Int> = emptyMap(),
    val foodDepleted: Boolean = false,
)

/** tick / アクションが出すイベント（UI・通知・チャットへの入力）。 */
sealed interface GameEvent {
    /** タイマー [job] が完了した。 */
    data class TimerCompleted(val job: TimerJob) : GameEvent

    /** スロット [slotId] の建物が [newLevel] になった。 */
    data class BuildingLevelUp(val slotId: Int, val type: BuildingType, val newLevel: Int) : GameEvent

    /** [resource] が上限に達した（達した瞬間に 1 回だけ）。 */
    data class StorageFull(val resource: ResourceType) : GameEvent

    /** バフ [id] が期限切れになった。 */
    data class BoostExpired(val id: String) : GameEvent

    /** 兵 [troopId] を [count] 体訓練し終えた。 */
    data class TroopsTrained(val troopId: String, val count: Long) : GameEvent

    /** 負傷兵 [batch] の治療が終わった。 */
    data class TroopsHealed(val batch: Map<String, Long>) : GameEvent

    /** 研究 [nodeId] が [level] になった。 */
    data class ResearchCompleted(val nodeId: String, val level: Int) : GameEvent

    /** 維持費で食料が 0 に達した（達した瞬間に 1 回だけ）。 */
    data object FoodDepleted : GameEvent

    /** 英雄を召喚した。 */
    data class HeroSummoned(val result: SummonResult) : GameEvent
}
