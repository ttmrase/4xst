package com.ttmrase.crownfall.core.chat

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.model.PendingChat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTest {
    private val data = TestSupport.data
    private val tile = TileRef(3, 4)

    private val all: List<ChatEvent> =
        listOf(
            ChatEvent.PlayerHelped(3),
            ChatEvent.PlayerDonated(12_500, "tech_gather"),
            ChatEvent.PlayerJoinedRally(1, true, "kaede"),
            ChatEvent.PlayerJoinedRally(1, false, "marcus"),
            ChatEvent.PlayerCastleUp(14),
            ChatEvent.PlayerDefeated("raid"),
            ChatEvent.PlayerDefended("raid"),
            ChatEvent.NpcNeedsHelp("ponzu", "stone", 50_000, 2),
            ChatEvent.NpcAsksScout("marcus", tile, 3),
            ChatEvent.NpcPurchased("johnny", "pack_war", 4),
            ChatEvent.NpcCallsRally("kaede", 5, 2, tile),
            ChatEvent.NpcThanks("ponzu", "wood", 3000),
            ChatEvent.NpcThanks("marcus", "scout", 0),
            ChatEvent.Ambient("weather"),
            ChatEvent.WorldAmbient("game"),
            ChatEvent.DailyGreeting,
            ChatEvent.PlayerFreeText("やあ", ChatKeys.ALLIANCE),
            ChatEvent.EventRanking(3),
            ChatEvent.FavorMilestone("gen", 2),
            ChatEvent.PonzuQuiz("q_wall"),
            ChatEvent.QuizResult("q_wall", true),
            ChatEvent.QuizResult("q_wall", false),
            ChatEvent.YukiReturn(6),
        )

    @Test
    fun everyEventHasATemplateKeyThatExistsInTheTemplateFile() {
        for (e in all) {
            val key = e.key
            val base = if (e is ChatEvent.PlayerFreeText) "${ChatKeys.PLAYER_FREE_TEXT}.chat" else key
            assertTrue(base in ChatKeys.ALL, "$key is not listed in ChatKeys.ALL")
            assertTrue(data.templates.templates.containsKey(base), "templates.json has no $base")
        }
        assertEquals(ChatKeys.ALL.toSet().size, ChatKeys.ALL.size)
        assertTrue(ChatKeys.ALL.all { it in data.templates.templates })
    }

    @Test
    fun placeholdersAreFilledFromTheEventAndState() {
        var s = TestSupport.newGame().copy(playerName = "アレン")
        s = s.copy(alliance = s.alliance.copy(favorLevel = mapOf("kaede" to 3, "gen" to 2)))
        val v = ChatContext.vars(s, ChatEvent.PlayerDonated(12_500, "tech_gather"), data)
        assertEquals("12.5K", v.values["amount"])
        assertEquals("採集テック", v.values["tech"])
        assertEquals("アレン", v.values["player"])
        assertEquals("アレンはん", v.nicknameFor("kaede"))
        assertEquals("アレン", v.nicknameFor("gen"))
        assertEquals("領主さん", v.nicknameFor("marcus"))
        assertEquals("領主さん", v.nicknameFor(null))
        assertEquals("領主さん", v.defaultNickname)
        val need = ChatContext.vars(s, ChatEvent.NpcNeedsHelp("ponzu", "stone", 50_000, 2), data)
        assertEquals("石", need.values["resource"])
        assertEquals("50.0K", need.values["amount"])
        assertEquals("(3, 4)", ChatContext.vars(s, ChatEvent.NpcAsksScout("marcus", tile, 3), data).values["tile"])
        val rally = ChatContext.vars(s, ChatEvent.NpcCallsRally("kaede", 5, 2, tile), data)
        assertEquals("2", rally.values["fortLevel"])
        assertEquals("(3, 4)", rally.values["tile"])
        assertEquals(
            "成長パック",
            ChatContext.vars(s, ChatEvent.NpcPurchased("johnny", "pack_growth", 4), data).values["sku"],
        )
        assertEquals("Kaede", ChatContext.vars(s, ChatEvent.PlayerJoinedRally(1, true, "kaede"), data).values["host"])
        assertEquals("14", ChatContext.vars(s, ChatEvent.PlayerCastleUp(14), data).values["castleLv"])
        assertEquals("3", ChatContext.vars(s, ChatEvent.PlayerHelped(3), data).values["count"])
        assertEquals("3", ChatContext.vars(s, ChatEvent.EventRanking(3), data).values["rank"])
        assertEquals("2", ChatContext.vars(s, ChatEvent.FavorMilestone("gen", 2), data).values["lv"])
        assertEquals("6", ChatContext.vars(s, ChatEvent.YukiReturn(6), data).values["helpCount"])
        assertEquals("食料", ChatContext.vars(s, ChatEvent.NpcThanks("ponzu", "food", 1), data).values["resource"])
        assertEquals("領主さん", ChatContext.vars(TestSupport.newGame(), ChatEvent.DailyGreeting, data).values["player"])
    }

    @Test
    fun chatEventsSurviveTheSaveFormat() {
        val json = Json
        for (e in all) {
            val p = PendingChat(9, e, 123, listOf("kaede"), ChatKeys.ALLIANCE)
            assertEquals(p, json.decodeFromString<PendingChat>(json.encodeToString(p)), e.toString())
        }
        assertEquals("dm_gen", ChatKeys.dm("gen"))
    }
}
