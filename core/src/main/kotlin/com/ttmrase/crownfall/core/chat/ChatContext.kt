package com.ttmrase.crownfall.core.chat

import com.ttmrase.crownfall.core.alliance.Personas
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.util.ShortNumber

/**
 * テンプレのプレースホルダの値。[values] は共通の値、[nicknames] はペルソナごとの呼び方（好感度で変わる）。
 * プレースホルダ: {player} {resource} {amount} {castleLv} {tile} {rank} {nickname} {count} {lv} {fortLevel}
 * {tech} {sku} {helpCount} {host}。
 */
data class ChatVars(
    val values: Map<String, String>,
    val nicknames: Map<String, String>,
    val defaultNickname: String,
) {
    /** ペルソナ [persona] の呼び方。 */
    fun nicknameFor(persona: String?): String = persona?.let { nicknames[it] } ?: defaultNickname
}

/** 状態とチャットのきっかけからプレースホルダの値を作る（純関数）。 */
object ChatContext {
    /** [event] 用の [ChatVars]。 */
    fun vars(
        state: GameState,
        event: ChatEvent,
        data: GameData,
    ): ChatVars {
        val labels = data.templates.labels
        val default = labels["call.default"] ?: DEFAULT_CALL
        val nicknames =
            data.personas.personas.associate { def ->
                val lv = state.alliance.favorLevel[def.id] ?: 0
                def.id to Personas.callName(def, lv, state.playerName, default)
            }
        val v = LinkedHashMap<String, String>()
        v["player"] = state.playerName.ifBlank { default }
        v["castleLv"] = Marching.castleLevel(state).toString()
        when (event) {
            is ChatEvent.PlayerHelped -> v["count"] = event.count.toString()
            is ChatEvent.PlayerDonated -> {
                v["amount"] = ShortNumber.format(event.amount)
                v["tech"] = labels["tech.${event.tech}"] ?: event.tech
            }
            is ChatEvent.PlayerJoinedRally -> v["host"] = hostName(event.host, data)
            is ChatEvent.PlayerCastleUp -> v["castleLv"] = event.lv.toString()
            is ChatEvent.NpcNeedsHelp -> {
                v["resource"] = labels["resource.${event.resource}"] ?: event.resource
                v["amount"] = ShortNumber.format(event.amount)
            }
            is ChatEvent.NpcAsksScout -> v["tile"] = "(${event.tile.x}, ${event.tile.y})"
            is ChatEvent.NpcPurchased -> v["sku"] = labels["sku.${event.sku}"] ?: event.sku
            is ChatEvent.NpcCallsRally -> {
                v["tile"] = "(${event.tile.x}, ${event.tile.y})"
                v["fortLevel"] = event.fortLevel.toString()
            }
            is ChatEvent.NpcThanks -> {
                v["resource"] = labels["resource.${event.requestKind}"] ?: event.requestKind
                v["amount"] = ShortNumber.format(event.amount)
            }
            is ChatEvent.EventRanking -> v["rank"] = event.rank.toString()
            is ChatEvent.FavorMilestone -> v["lv"] = event.lv.toString()
            is ChatEvent.YukiReturn -> v["helpCount"] = event.helpCount.toString()
            else -> Unit
        }
        return ChatVars(v, nicknames, default)
    }

    private fun hostName(
        id: String,
        data: GameData,
    ): String = data.personas.persona(id)?.displayName ?: id

    private const val DEFAULT_CALL = "領主さん"
}
