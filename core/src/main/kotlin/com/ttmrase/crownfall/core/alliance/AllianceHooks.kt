package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.LogKind
import com.ttmrase.crownfall.core.model.RequestKind

/** :core の他の処理（行軍・襲撃・建設）から同盟へ知らせる口。 */
internal object AllianceHooks {
    private const val LEADER = "kaede"
    private const val ANALYST = "lin"
    private const val VETERAN = "gen"

    /** 偵察した [tile] への要請があれば達成扱いにする（好感度・同盟ポイント・お礼）。 */
    fun onScouted(
        state: GameState,
        tile: TileRef,
        data: GameData,
    ): GameState {
        val hits = state.alliance.requests.filter { it.kind == RequestKind.SCOUT && it.tile == tile }
        if (hits.isEmpty()) return state
        val r = data.allianceRules.request
        var s = state.copy(alliance = state.alliance.copy(requests = state.alliance.requests - hits.toSet()))
        for (req in hits) {
            s = AllianceLogic.addPoints(s, r.scoutPoints)
            s = AllianceLogic.addFavor(s, req.persona, r.scoutFavor, data)
            s = AllianceLogic.logged(s, LogKind.SCOUTED, req.persona, 0, data)
            val thanks = ChatEvent.NpcThanks(req.persona, "scout", 0)
            s = AllianceLogic.enqueueFrom(s, thanks, req.persona, ChatKeys.ALLIANCE, data)
        }
        return s
    }

    /** AI 主催ラリーに参加した行軍の戦闘が終わった。 */
    fun onRallyResult(
        state: GameState,
        hostPersona: String,
        rallyId: Long,
        won: Boolean,
        data: GameData,
    ): GameState {
        var s = AllianceLogic.addFavor(state, hostPersona, data.allianceRules.request.favor, data)
        s = AllianceLogic.logged(s, LogKind.JOINED_RALLY, hostPersona, 0, data)
        val event = ChatEvent.PlayerJoinedRally(rallyId, won, hostPersona)
        return AllianceLogic.enqueueReactive(s, event, listOf(hostPersona), data)
    }

    /** 城が [lv] になった。 */
    fun onCastleUp(
        state: GameState,
        lv: Int,
        data: GameData,
    ): GameState = AllianceLogic.enqueueReactive(state, ChatEvent.PlayerCastleUp(lv), listOf(LEADER, ANALYST), data)

    /** 襲撃の結果。 */
    fun onRaid(
        state: GameState,
        playerWon: Boolean,
        data: GameData,
    ): GameState {
        val event = if (playerWon) ChatEvent.PlayerDefended("raid") else ChatEvent.PlayerDefeated("raid")
        return AllianceLogic.enqueueReactive(state, event, listOf(LEADER, VETERAN), data)
    }
}
