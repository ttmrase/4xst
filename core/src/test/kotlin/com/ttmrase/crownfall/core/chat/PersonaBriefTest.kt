package com.ttmrase.crownfall.core.chat

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.PendingChat
import com.ttmrase.crownfall.core.model.Resources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 端末内 LLM 向けの要約（PersonaBrief・表示名の表・状況の材料）の組み立て。 */
class PersonaBriefTest {
    private val data = TestSupport.data
    private val state = TestSupport.newGame(now = 1_000_000L).copy(playerName = "アレン")

    private fun pending(
        event: ChatEvent,
        vararg speakers: String,
    ) = PendingChat(1, event, 1_000_000L, speakers.toList(), ChatKeys.ALLIANCE)

    @Test
    fun theBriefHasDisplayNameCallNameRoleTraitsRelationVoiceAndExamples() {
        val s = state.copy(alliance = state.alliance.copy(favorLevel = mapOf("kaede" to 4, "gen" to 0)))
        val briefs = PromptContextBuilder.briefs(s, pending(ChatEvent.DailyGreeting, "kaede", "gen"), data)
        val kaede = briefs.first { it.id == "kaede" }
        assertEquals("Kaede", kaede.displayName)
        assertEquals("R5の同盟のリーダー", kaede.role)
        assertTrue("社交的" in kaede.traits && "面倒見がよい" in kaede.traits && "好戦的" !in kaede.traits, kaede.traits)
        assertEquals("相棒", kaede.relation)
        assertTrue("関西弁" in kaede.voice && "やで" in kaede.voice, kaede.voice)
        assertEquals(5, kaede.examples.size)
        assertEquals(kaede.examples.sortedBy { it.length }, kaede.examples)
        assertTrue(kaede.callName.isNotBlank())
        val gen = briefs.first { it.id == "gen" }
        assertEquals("初対面", gen.relation)
        assertTrue("控えめ" in gen.traits && "真面目" in gen.traits, gen.traits)
    }

    @Test
    fun relationWordsFollowTheFavorLevel() {
        val kaede = data.personas.persona("kaede")!!
        val words = (0..4).map { PromptContextBuilder.brief(kaede, "x", it).relation }
        assertEquals(listOf("初対面", "顔なじみ", "親しい", "親しい", "相棒"), words)
    }

    @Test
    fun everyPersonaGetsARoleAndAVoice() {
        for (p in data.personas.personas) {
            val b = PromptContextBuilder.brief(p, "領主さん")
            assertTrue(b.role.startsWith("R${p.rank}の"), "${p.id} ${b.role}")
            assertTrue(b.voice.contains("語尾は"), "${p.id} ${b.voice}")
            assertTrue(b.examples.isNotEmpty())
        }
    }

    @Test
    fun theNameTableMapsIdsToDisplayNamesAndThePlayerToTheirName() {
        val names = PromptContextBuilder.names(state, data)
        assertEquals("Kaede", names["kaede"])
        assertEquals("アレン", names["player"])
        assertTrue(PromptContextBuilder.names(state.copy(playerName = ""), data).getValue("player").isNotBlank())
    }

    @Test
    fun theSituationShowsDisplayNamesNotIds() {
        val event = ChatEvent.NpcNeedsHelp("kaede", "wood", 500L, 1L)
        val text = PromptContextBuilder.situation(state, pending(event, "kaede"), data)
        assertTrue("Kaede" in text, text)
        assertFalse(Regex("(?<![A-Za-z0-9_])kaede(?![A-Za-z0-9_])").containsMatchIn(text), text)
    }

    @Test
    fun theWorldLineAndTheStatusLineAreShort() {
        assertEquals("[CRW] Crown Wardens（メンバー 30 名）", PromptContextBuilder.worldLine(data))
        val status = PromptContextBuilder.statusLine(state, data)
        assertTrue(status.startsWith("城Lv1。"), status)
        val poor = state.copy(resources = Resources(0, 0, 0, 0))
        assertTrue("不足" in PromptContextBuilder.statusLine(poor, data))
        val plenty = state.copy(resources = Resources(10_000_000, 10_000_000, 10_000_000, 10_000_000))
        assertTrue("満杯近い" in PromptContextBuilder.statusLine(plenty, data))
    }

    @Test
    fun theQuizIdDoesNotLeakIntoTheSituationAndTheBigPromptKeepsTheEmojiHint() {
        val quiz = data.templates.quizzes.keys.first()
        val text = PromptContextBuilder.situation(state, pending(ChatEvent.PonzuQuiz(quiz), "ponzu"), data)
        assertFalse(quiz in text, text)
        assertTrue("ぽんず" in text || "ポンズ" in text || "初心者" in text, text)
        val system = PromptContextBuilder.build(state, pending(ChatEvent.DailyGreeting, "kaede"), data).system
        assertTrue("絵文字は使いすぎない" in system)
    }
}
