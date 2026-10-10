package com.ttmrase.crownfall.debug

import com.ttmrase.crownfall.AppContainer
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.data.Startup
import kotlinx.coroutines.flow.first

/**
 * デバッグビルド専用: エミュレータの UI テスト（androidTest）が、決まった状態からゲームを始めるための注入口。
 * release には含まれない（src/debug）。使い方: `DebugStateSeeder.seed(container, Preset.RICH_CASTLE)` のあと、
 * タイトルの「続きから」を押す。状態は新規ゲームを作ってから丸ごと置き換え、すぐ保存する。
 */
object DebugStateSeeder {
    /** 用意できる状態。名前は screenshot のファイル名にも使う。 */
    enum class Preset {
        /** 城 Lv12、他の建物も育っていて、資源とジェムが十分ある。 */
        RICH_CASTLE,

        /** 城 Lv24・ほかの建物も Lv24（見た目の最上段階。領地は最大まで広がる）。 */
        LATE_GAME,

        /** RICH_CASTLE + 農場の建設中（足場・槌・土埃の見た目）。 */
        BUILDING_NOW,

        /** RICH_CASTLE + 本日限定オファー（ログインのパック）が出ている。 */
        OFFER_READY,

        /** RICH_CASTLE + 次の襲撃まで 15 分（物見の塔 Lv8、1 時間前までの段階は通過済み）。 */
        RAID_IN_15_MIN,

        /** RICH_CASTLE + VIP をあと 1 pt で昇格する直前にする。 */
        VIP_NEAR,

        /** RICH_CASTLE + 召喚の天井直前（SSR 確定まであと 1 回）でジェムが十分ある。 */
        GACHA_READY,
    }

    /** [preset] の状態を注入して保存する。新規ゲームが無ければ作る。 */
    suspend fun seed(
        container: AppContainer,
        preset: Preset,
        playerName: String = "Debug",
        starterHeroId: String = "",
    ) {
        val ready = container.repository.startup.first { it is Startup.Ready } as Startup.Ready
        val hero = starterHeroId.ifEmpty { ready.data.heroes.keys.first() }
        container.repository.startNewGame(playerName, hero)
        val data = ready.data
        ready.session.dispatch { s -> ActionResult.Success(build(s, preset, data), tracked = true) }
        ready.session.flush()
    }

    /** 純関数。[state] を [preset] の状態にする。 */
    fun build(
        state: GameState,
        preset: Preset,
        data: GameData,
    ): GameState {
        val rich = rich(state, data)
        return when (preset) {
            Preset.RICH_CASTLE -> rich
            Preset.LATE_GAME -> late(rich, data)
            Preset.BUILDING_NOW -> building(rich, data)
            Preset.OFFER_READY -> withLoginOffer(rich, data)
            Preset.RAID_IN_15_MIN -> raidSoon(rich, data)
            Preset.VIP_NEAR -> vipNear(rich, data)
            Preset.GACHA_READY -> rich.copy(summonPity = PITY_NEAR)
        }
    }

    private fun rich(
        state: GameState,
        data: GameData,
    ): GameState {
        val castleTarget = CASTLE_LEVEL.coerceAtMost(data.building(BuildingType.CASTLE).maxLevel)
        val slots =
            state.slots.map { slot ->
                val max = data.building(slot.type).maxLevel
                val target = if (slot.type == BuildingType.CASTLE) castleTarget else (castleTarget - 2).coerceAtLeast(1)
                slot.copy(level = target.coerceAtMost(max))
            }
        return state.copy(
            slots = slots,
            resources = Resources(RICH, RICH, RICH, RICH),
            gems = RICH_GEMS,
        )
    }

    private fun late(
        state: GameState,
        data: GameData,
    ): GameState =
        state.copy(
            slots = state.slots.map { it.copy(level = LATE_LEVEL.coerceAtMost(data.building(it.type).maxLevel)) },
        )

    private fun building(
        state: GameState,
        data: GameData,
    ): GameState {
        val farm = state.slots.first { it.type == BuildingType.FARM }
        return when (val r = Actions.startUpgrade(state, farm.slotId, data)) {
            is ActionResult.Success -> r.state
            is ActionResult.Failure -> state
        }
    }

    private fun withLoginOffer(
        state: GameState,
        data: GameData,
    ): GameState {
        val pack = data.offers.pack(data.shopRules.loginPackSku) ?: return state
        val offer = OfferEngine.makeOffer(state, pack, "login", data)
        val next = OfferEngine.enqueue(state, offer, 0, data, bypass = true)
        // ポップアップを出してよい状態にする（間隔・停止中のフラグを戻す）
        return next.copy(shop = next.shop.copy(popupsStopped = false, lastPopupClosedEpochSec = 0))
    }

    private fun raidSoon(
        state: GameState,
        data: GameData,
    ): GameState {
        val towerSlots =
            state.slots.map { if (it.type == BuildingType.WATCHTOWER) it.copy(level = TOWER_LEVEL) else it }
        val stagesBefore = data.notifyRules.raid.stages.indexOfFirst { it.offsetSec <= RAID_SOON_SEC }
        return state.copy(
            slots = towerSlots,
            map =
                state.map.copy(
                    nextRaidEpochSec = state.nowEpochSec + RAID_SOON_SEC,
                    raidWarned = true,
                    raidStageDone = stagesBefore.coerceAtLeast(0),
                ),
        )
    }

    private fun vipNear(
        state: GameState,
        data: GameData,
    ): GameState {
        // VIP Lv に関わらず、次の Lv の閾値より 1 pt 手前にする
        val level = VipCalculator.level(state, data).coerceAtLeast(NEAR_VIP_FROM)
        val next = data.vip.levels.getOrNull(level) ?: return state
        return state.copy(shop = state.shop.copy(vipPoints = next.pt - 1))
    }

    private const val CASTLE_LEVEL = 12
    private const val LATE_LEVEL = 24
    private const val TOWER_LEVEL = 8
    private const val RICH = 5_000_000L
    private const val RICH_GEMS = 100_000L
    private const val RAID_SOON_SEC = 900L
    private const val NEAR_VIP_FROM = 4

    /** premium の天井（pityAt=100）の 1 回前。 */
    private const val PITY_NEAR = 99
}
