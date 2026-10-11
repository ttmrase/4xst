package com.ttmrase.crownfall.core.model

import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.map.MapState
import com.ttmrase.crownfall.core.march.March
import com.ttmrase.crownfall.core.march.MarchKind
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
    /** プレイヤーのアバター番号（0 始まり。表示側の候補数で割った余りを使う。M11 T11.5）。 */
    val avatar: Int = 0,
    /** 名乗っている称号（titles.json の id）。null なら、名乗れるうちで一番格の高いもの。M11 T11.3。 */
    val selectedTitle: String? = null,
    /** 収穫バブルを最後に回収した時刻（スロット ID → epoch 秒）。無い建物は未回収（すぐ出る）。M11 T11.2。 */
    val lastBubbleCollect: Map<Int, Long> = emptyMap(),
    /** 城の昇格の演出を見せ終えた最高の Lv。0 は未記録（旧セーブ。最初に現在の Lv へ黙って追従する）。M11 T11.3。 */
    val celebratedCastleLv: Int = 0,
    val starterHeroId: String? = null,
    val tutorialStep: Int = 0,
    val troops: Map<String, Long> = emptyMap(),
    val wounded: Map<String, Long> = emptyMap(),
    val research: Map<String, Int> = emptyMap(),
    val heroes: List<HeroState> = emptyList(),
    val summonPity: Int = 0,
    val heroShards: Map<String, Int> = emptyMap(),
    val foodDepleted: Boolean = false,
    val map: MapState = MapState(),
    val mails: List<Mail> = emptyList(),
    val nextMailId: Long = 1,
    val marches: List<March> = emptyList(),
    val nextMarchId: Long = 1,
    val alliance: AllianceState = AllianceState(),
    val settings: Settings = Settings(),
    val shop: ShopState = ShopState(),
    val quest: QuestState = QuestState(),
    val event: EventState = EventState(),
    val features: FeatureProgress = FeatureProgress(),
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

    /** メールを受け取った。 */
    data class MailReceived(val mailId: Long, val kind: MailKind) : GameEvent

    /** 行軍 [marchId] が帰還した。 */
    data class MarchReturned(val marchId: Long, val kind: MarchKind) : GameEvent

    /** 同盟メンバー [persona] がタイマー [jobId] にヘルプしてくれた（[reducedSec] 秒短縮）。 */
    data class HelpReceived(val persona: String, val jobId: Long, val reducedSec: Long) : GameEvent

    /** ギフト箱 [giftId]（贈り主 [from]）を開けて報酬を受け取った。 */
    data class GiftOpened(
        val giftId: Long,
        val from: String,
        val resources: Resources,
        val item: String?,
        val count: Int,
        val gems: Long,
    ) : GameEvent

    /** NPC の襲撃が [arrivalEpochSec] に到着する（予告）。 */
    data class RaidIncoming(val arrivalEpochSec: Long) : GameEvent

    /** 襲撃の段階に入った（見えない段階は出さない）。[defended] は RESULT のときだけ意味を持つ。 */
    data class RaidStageReached(
        val stage: RaidStage,
        val raidAt: Long,
        val defended: Boolean = false,
    ) : GameEvent

    /** 襲撃を撃退した。 */
    data class PlayerDefendedRaid(val mailId: Long) : GameEvent

    /** 襲撃に敗れ、資源を奪われた。 */
    data class PlayerDefeated(val mailId: Long) : GameEvent

    /** 擬似購入が完了した。[mailId] はレシートメール。 */
    data class Purchased(
        val sku: String,
        val priceYen: Long,
        val mailId: Long,
        val gems: Long,
        val orderA: Int,
        val orderB: Int,
    ) : GameEvent

    /** プレイヤーが同盟メンバーのタイマー [count] 件にヘルプした。 */
    data class HelpGiven(val count: Int) : GameEvent

    /** 同盟テックに寄付した（[amount] は寄付した量）。 */
    data class Donated(val amount: Long) : GameEvent

    /** AI 主催のラリーに参加した。 */
    data object RallyJoined : GameEvent

    /** 同盟 / DM チャットに書き込んだ。 */
    data object ChatPosted : GameEvent

    /** ショップ画面を開いた。 */
    data object ShopOpened : GameEvent

    /** 野蛮人 / 砦との戦いに勝った（[fort] なら砦、[level] はその Lv）。 */
    data class HuntWon(val fort: Boolean, val level: Int) : GameEvent

    /** 採集隊が戻った（[loot] は持ち帰った資源の合計）。 */
    data class GatherCompleted(
        val loot: Long,
        val resources: Resources = Resources.ZERO,
    ) : GameEvent

    /** VIP が [lv] に上がった。 */
    data class VipLevelUp(val lv: Int) : GameEvent

    /** 城 Lv の上昇で機能 [feature] が解放された。 */
    data class FeatureUnlocked(val feature: Feature) : GameEvent

    /** スキン福袋を引いた。[entries] は排出順（新規か、重複なら欠片がいくつか）。 */
    data class SkinBag(val entries: List<SkinBagEntry>) : GameEvent {
        val skinIds: List<String> get() = entries.map { it.skinId }
        val newCount: Int get() = entries.count { it.isNew }
    }

    /** ラッキーホイールの結果。[segment] は当たり、[tease] は「惜しい」演出で先に見せる位置（無ければ -1）。 */
    data class WheelResult(val segment: Int, val tease: Int, val segmentId: String) : GameEvent
}

/** 福袋 1 回ぶんの結果。[rarity] は N / R / SR / SSR、[shards] は重複で得た欠片（新規なら 0）。 */
data class SkinBagEntry(val skinId: String, val rarity: String, val isNew: Boolean, val shards: Int)
