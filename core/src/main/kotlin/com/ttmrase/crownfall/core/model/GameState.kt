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
}
