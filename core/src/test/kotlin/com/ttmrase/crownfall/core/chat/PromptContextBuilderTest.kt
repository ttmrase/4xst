package com.ttmrase.crownfall.core.chat

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.model.ActionLogEntry
import com.ttmrase.crownfall.core.model.BattleKind
import com.ttmrase.crownfall.core.model.BattleReport
import com.ttmrase.crownfall.core.model.ChatMessage
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.LogKind
import com.ttmrase.crownfall.core.model.Mail
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.PendingChat
import com.ttmrase.crownfall.core.model.Resources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptContextBuilderTest {
    private val data = TestSupport.data

    private fun state(): GameState {
        var s = TestSupport.newGame(now = 1_000_000L).copy(playerName = "アレン")
        val msgs =
            (1L..30L).map {
                ChatMessage(
                    it,
                    ChatKeys.ALLIANCE,
                    if (it % 2 == 0L) "player" else "kaede",
                    "行$it",
                    it,
                )
            }
        s = s.copy(alliance = s.alliance.copy(history = mapOf(ChatKeys.ALLIANCE to msgs)))
        return s
    }

    private fun pending(
        event: ChatEvent,
        vararg speakers: String,
    ) = PendingChat(1, event, 1_000_000L, speakers.toList(), ChatKeys.ALLIANCE)

    @Test
    fun systemPromptDescribesOnlyTheSpeakingCharactersAndTheRules() {
        val p = PromptContextBuilder.build(state(), pending(ChatEvent.DailyGreeting, "kaede", "gen"), data)
        assertTrue(p.system.contains("id=kaede") && p.system.contains("id=gen"))
        assertFalse(p.system.contains("id=marcus") || p.system.contains("id=ponzu"))
        assertTrue(p.system.contains("[CRW] Crown Wardens"))
        assertTrue(p.system.contains("城Lv1") && p.system.contains("戦力"))
        assertTrue(p.system.contains("{\"lines\":[{\"speaker\""))
        assertTrue(p.system.contains("否定も断定もせず"))
        assertTrue(p.system.contains("擬似課金"))
        assertTrue(p.system.contains("進行中のイベント順位"))
    }

    @Test
    fun revealAiSettingChangesTheInstruction() {
        val s = state()
        val ev = pending(ChatEvent.DailyGreeting, "kaede")
        val ambiguous = PromptContextBuilder.build(s, ev, data, RevealAi.AMBIGUOUS).system
        val admit = PromptContextBuilder.build(s, ev, data, RevealAi.ADMIT).system
        assertTrue(ambiguous.contains("はぐらかす") && !ambiguous.contains("正直に"))
        assertTrue(admit.contains("正直に") && !admit.contains("はぐらかす"))
    }

    @Test
    fun userPartHasTheLast20ChatLinesThenTheTrigger() {
        val p =
            PromptContextBuilder.build(
                state(),
                pending(ChatEvent.PlayerFreeText("資源が足りない", ChatKeys.ALLIANCE), "kaede"),
                data,
            )
        assertEquals(21, p.turns.size)
        assertEquals("行11", p.turns.first().text)
        assertEquals("行30", p.turns[19].text)
        val trigger = p.turns.last()
        assertEquals("system", trigger.speaker)
        assertTrue(trigger.text.contains("資源が足りない") && trigger.text.contains("アレン"))
    }

    @Test
    fun noSecretsOrIdentifiersEverEndUpInThePrompt() {
        val all =
            events().flatMap { e ->
                val p = PromptContextBuilder.build(state(), pending(e, "kaede", "yuki"), data)
                listOf(p.system) + p.turns.map { it.text }
            }.joinToString("\n")
        val forbidden =
            listOf("AIza", "api_key", "apikey", "Bearer", "android", "imei", "http://", "https://", "@gmail", "x-goog")
        for (bad in forbidden) {
            assertFalse(all.contains(bad, ignoreCase = true), "prompt contains $bad")
        }
    }

    private fun events(): List<ChatEvent> =
        listOf(
            ChatEvent.PlayerHelped(
                2,
            ),
            ChatEvent.PlayerDonated(10_000, "tech_hp"), ChatEvent.PlayerJoinedRally(1, false, "marcus"),
            ChatEvent.PlayerJoinedRally(
                1,
                true,
                "kaede",
            ),
            ChatEvent.PlayerCastleUp(9), ChatEvent.PlayerDefeated("raid"),
            ChatEvent.PlayerDefended(
                "raid",
            ),
            ChatEvent.NpcNeedsHelp("ponzu", "wood", 8000, 1), ChatEvent.NpcAsksScout("marcus", TileRef(5, 6), 2),
            ChatEvent.NpcPurchased("johnny", "pack_war", 3), ChatEvent.NpcCallsRally("kaede", 4, 2, TileRef(7, 8)),
            ChatEvent.NpcThanks("ponzu", "wood", 8000), ChatEvent.Ambient("event"), ChatEvent.WorldAmbient("life"),
            ChatEvent.DailyGreeting, ChatEvent.PlayerFreeText("やあ", ChatKeys.ALLIANCE), ChatEvent.EventRanking(3),
            ChatEvent.FavorMilestone("gen", 2), ChatEvent.PonzuQuiz("q_wall"), ChatEvent.QuizResult("q_wall", true),
            ChatEvent.QuizResult("q_wall", false), ChatEvent.YukiReturn(5),
        )

    @Test
    fun everyTriggerIsDescribedWithItsDetails() {
        val s = state()
        for (e in events()) {
            val text = PromptContextBuilder.describe(s, pending(e, "kaede"), data)
            assertTrue(text.length > 20, "$e -> $text")
            assertFalse(text.contains("{") || text.contains("null"), text)
        }
        assertTrue(PromptContextBuilder.describe(s, pending(ChatEvent.YukiReturn(5), "yuki"), data).contains("5回"))
        assertTrue(
            PromptContextBuilder.describe(
                s,
                pending(ChatEvent.NpcAsksScout("marcus", TileRef(5, 6), 2), "marcus"),
                data,
            ).contains("(5, 6)"),
        )
        assertTrue(
            PromptContextBuilder.describe(
                s,
                pending(ChatEvent.NpcNeedsHelp("ponzu", "wood", 8000, 1), "ponzu"),
                data,
            ).contains("木材"),
        )
    }

    @Test
    fun recentEventsComeFromMailsAndLogNewestFirstCappedAtFive() {
        var s = state()
        val report = { kind: BattleKind, won: Boolean ->
            BattleReport(kind, null, 1, won, 3, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
        val mails =
            listOf(
                Mail(1, MailKind.BATTLE, 100, battle = report(BattleKind.RAID, false)),
                Mail(2, MailKind.BATTLE, 200, battle = report(BattleKind.BARBARIAN, true)),
                Mail(3, MailKind.BATTLE, 300, battle = report(BattleKind.FORT, false)),
                Mail(4, MailKind.SCOUT, 400),
                Mail(5, MailKind.SYSTEM, 500),
            )
        val log = LogKind.entries.mapIndexed { i, k -> ActionLogEntry(600L + i, k, "kaede", 1) }
        s = s.copy(mails = mails, alliance = s.alliance.copy(actionLog = log))
        val ev = PromptContextBuilder.recentEvents(s)
        assertEquals(5, ev.size)
        assertEquals("同盟テックに寄付した", ev.first())
        assertEquals(
            1,
            PromptContextBuilder.recentEvents(
                s.copy(alliance = s.alliance.copy(actionLog = emptyList()), mails = mails.take(1)),
            ).size,
        )
        assertTrue(
            PromptContextBuilder.recentEvents(
                s.copy(mails = mails.take(1), alliance = s.alliance.copy(actionLog = emptyList())),
            ).single().contains("襲撃を撃退した"),
        )
        val kinds =
            mails.map {
                PromptContextBuilder.recentEvents(
                    s.copy(mails = listOf(it), alliance = s.alliance.copy(actionLog = emptyList())),
                ).single()
            }
        assertEquals(listOf("襲撃を撃退した", "野蛮人に勝った", "砦のラリーに負けた", "偵察報告を受け取った", "お知らせ"), kinds)
        assertTrue(
            PromptContextBuilder.recentEvents(
                s.copy(mails = emptyList(), alliance = s.alliance.copy(actionLog = emptyList())),
            ).isEmpty(),
        )
    }

    @Test
    fun resourceLevelsAreSummarisedAsShortageOrFull() {
        val cap = com.ttmrase.crownfall.core.sim.Simulation.storageCap(state(), data)
        val s = state().copy(resources = Resources(0, cap, cap / 2, cap / 2))
        val sys = PromptContextBuilder.build(s, pending(ChatEvent.DailyGreeting, "kaede"), data).system
        assertTrue(sys.contains("食料不足") && sys.contains("木材満杯近い") && sys.contains("石普通"))
    }
}
