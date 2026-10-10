package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.AiResult
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.SpokenLine
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.chat.ChatVars
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.map.TileRef
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScriptedProviderTest {
    private val data = ClasspathDataSource.load()
    private val provider = ScriptedProvider(data)
    private val ids = data.personas.personas.map { it.id }

    private val vars =
        ChatVars(
            values =
                mapOf(
                    "player" to "アレン", "resource" to "食料", "amount" to "5.0K", "castleLv" to "12", "tile" to "(10, 20)",
                    "rank" to "3", "count" to "4", "lv" to "2", "fortLevel" to "3", "tech" to "生産テック",
                    "sku" to "成長パック", "helpCount" to "6", "host" to "Kaede",
                ),
            nicknames = ids.associateWith { "アレン" },
            defaultNickname = "領主さん",
        )

    private fun events(persona: String): List<ChatEvent> =
        listOf(
            ChatEvent.PlayerHelped(3),
            ChatEvent.PlayerDonated(10_000, "tech_production"),
            ChatEvent.PlayerJoinedRally(1, true, "kaede"),
            ChatEvent.PlayerJoinedRally(1, false, "kaede"),
            ChatEvent.PlayerCastleUp(12),
            ChatEvent.PlayerDefeated("raid"),
            ChatEvent.PlayerDefended("raid"),
            ChatEvent.NpcNeedsHelp(persona, "food", 5000, 1),
            ChatEvent.NpcAsksScout(persona, TileRef(10, 20), 2),
            ChatEvent.NpcPurchased(persona, "pack_growth", 3),
            ChatEvent.NpcCallsRally(persona, 4, 3, TileRef(10, 20)),
            ChatEvent.NpcThanks(persona, "food", 5000),
            ChatEvent.NpcThanks(persona, "scout", 0),
            ChatEvent.Ambient("weather"),
            ChatEvent.WorldAmbient("game"),
            ChatEvent.DailyGreeting,
            ChatEvent.EventRanking(3),
            ChatEvent.FavorMilestone(persona, 2),
            ChatEvent.PonzuQuiz("q_wall"),
            ChatEvent.QuizResult("q_wall", true),
            ChatEvent.QuizResult("q_wall", false),
            ChatEvent.YukiReturn(6),
        ) +
            listOf("資源が足りない", "戦いに勝てない", "研究の順番は？", "こんにちは", "ありがとう", "どうすればいい？").map {
                ChatEvent.PlayerFreeText(it, ChatKeys.ALLIANCE)
            }

    private fun request(
        event: ChatEvent,
        speakers: List<String>,
        seed: Long = 7,
        recent: List<String> = emptyList(),
    ) = ChatRequest("sys", emptyList(), event = event, speakers = speakers, seed = seed, recent = recent, vars = vars)

    private fun run(r: ChatRequest): List<SpokenLine> {
        val res = runBlocking { provider.complete(r) }
        return (res as AiResult.Success).response.lines
    }

    @Test
    fun everyEventAndEveryPersonaGetsAReplyWithoutKeys() {
        for (p in ids) {
            for (ev in events(p)) {
                val lines = run(request(ev, listOf(p) + ids.filter { it != p }.take(2)))
                assertTrue(lines.isNotEmpty(), "$p ${ev.key}")
                for (l in lines) {
                    assertTrue(l.text.isNotBlank(), "blank: $p ${ev.key}")
                    assertFalse(l.text.contains('{') || l.text.contains('}'), "placeholder left: ${l.text}")
                    assertTrue(l.speaker in ids || l.speaker.startsWith("world:"), l.speaker)
                }
            }
        }
    }

    @Test
    fun everyKeyHasAtLeastFiveTemplatesPerPersonaAndKnownPlaceholders() {
        val allowed =
            "player nickname resource amount castleLv tile rank count lv fortLevel tech sku helpCount host".split(
                " ",
            ).toSet()
        val special =
            setOf(ChatKeys.WORLD_AMBIENT, ChatKeys.YUKI_RETURN, ChatKeys.YUKI_RETURN_NONE, ChatKeys.PONZU_QUIZ)
        for (key in ChatKeys.ALL) {
            val per = data.templates.templates[key] ?: error("missing key $key")
            if (key in special) {
                assertTrue((per["*"] ?: per["yuki"] ?: per["ponzu"]).orEmpty().size >= 5, key)
            } else {
                for (p in ids) assertTrue(per[p].orEmpty().size >= 5, "$key $p has ${per[p]?.size}")
            }
            for (list in per.values) for (t in list) {
                for (m in Regex(
                    "\\{(\\w+)\\}",
                ).findAll(t)) assertTrue(m.groupValues[1] in allowed, "unknown {${m.groupValues[1]}} in $t")
            }
        }
    }

    @Test
    fun outputIsDeterministicAndSeedDependent() {
        val r = request(ChatEvent.Ambient("life"), listOf("kaede", "marcus", "lin"))
        assertEquals(run(r), run(r))
        val distinct =
            (1L..30L).map {
                run(request(ChatEvent.Ambient("life"), listOf("kaede", "marcus", "lin"), seed = it))
            }.toSet()
        assertTrue(distinct.size > 10)
    }

    @Test
    fun ambientGivesTwoToFourLinesAndReactionsAtMostTheSpeakerCount() {
        val counts =
            (1L..60L).map {
                run(
                    request(ChatEvent.Ambient("x"), listOf("kaede", "sato"), seed = it),
                ).size
            }.toSet()
        assertTrue(counts.all { it in 2..4 } && counts.size >= 2, "$counts")
        val one = (1L..40L).map { run(request(ChatEvent.PlayerHelped(2), listOf("kaede"), seed = it)).size }.toSet()
        assertEquals(setOf(1), one)
        val two =
            (1L..40L).map {
                run(
                    request(ChatEvent.PlayerHelped(2), listOf("kaede", "gen"), seed = it),
                ).size
            }.toSet()
        assertEquals(setOf(1, 2), two)
    }

    @Test
    fun speakersComeOnlyFromTheActiveListOrTheEventPersona() {
        for (seed in 1L..40L) {
            val lines = run(request(ChatEvent.DailyGreeting, listOf("gen", "sato"), seed = seed))
            assertTrue(lines.all { it.speaker in setOf("gen", "sato") })
            val npc = run(request(ChatEvent.NpcNeedsHelp("ponzu", "wood", 3000, 1), listOf("gen"), seed = seed))
            assertEquals(listOf("ponzu"), npc.map { it.speaker })
        }
    }

    @Test
    fun recentlyUsedTextsAreAvoided() {
        val base = request(ChatEvent.PlayerCastleUp(12), listOf("kaede"), seed = 5)
        val first = run(base).first().text
        var recent = listOf(first)
        repeat(4) {
            val next = run(base.copy(recent = recent)).first().text
            assertFalse(recent.any { norm(it) == norm(next) }, "repeated: $next in $recent")
            recent = recent + next
        }
    }

    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }

    @Test
    fun freeTextIsClassifiedByKeyword() {
        assertEquals("thanks", FreeTextClassifier.classify("ありがとう！"))
        assertEquals("resource", FreeTextClassifier.classify("木材が足りない"))
        assertEquals("battle", FreeTextClassifier.classify("野蛮人に勝てる？"))
        assertEquals("research", FreeTextClassifier.classify("研究の順番"))
        assertEquals("question", FreeTextClassifier.classify("どうやるの"))
        assertEquals("chat", FreeTextClassifier.classify("こんにちは"))
        assertEquals("thanks", FreeTextClassifier.classify("Thanks!"))
    }

    @Test
    fun quizQuestionComesFromTheQuizTable() {
        val lines = run(request(ChatEvent.PonzuQuiz("q_wall"), listOf("kaede")))
        assertEquals(listOf("ponzu"), lines.map { it.speaker })
        assertEquals(data.templates.quizzes.getValue("q_wall").question, lines.single().text)
        val unknown = run(request(ChatEvent.PonzuQuiz("nope"), listOf("kaede")))
        assertTrue(unknown.single().text.isNotBlank())
    }

    @Test
    fun worldChatUsesOtherAllianceNames() {
        val lines = run(request(ChatEvent.WorldAmbient("game"), emptyList()))
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.speaker.removePrefix("world:") in data.personas.worldNpcNames })
    }

    @Test
    fun styleDiffersByPersona() {
        val johnny = data.personas.persona("johnny")!!
        val gen = data.personas.persona("gen")!!
        val styled = (1L..200L).map { StyleApplier.apply("やったね！", johnny, it, 0) }
        assertTrue(styled.any { it.contains("〜") }, "tilde")
        assertTrue(styled.any { it.any { c -> c.code > 0x2000 && c !in "。！？〜" } && it.length > 5 }, "emoji")
        assertTrue((1L..200L).all { StyleApplier.apply("備えよ", gen, it, 0).none { c -> Character.isSurrogate(c) } })
        assertEquals("", StyleApplier.apply("  ", gen, 1, 0))
        assertEquals(StyleApplier.apply("こんにちは", johnny, 9, 3), StyleApplier.apply("こんにちは", johnny, 9, 3))
        // 句読点のくせ（gen は「。」）
        assertTrue((1L..50L).map { StyleApplier.apply("焦るな", gen, it, 0) }.any { it.endsWith("。") })
    }

    @Test
    fun differentPersonasSoundDifferent() {
        val texts = ids.map { p -> run(request(ChatEvent.PlayerHelped(3), listOf(p), seed = 3)).first().text }
        assertEquals(ids.size, texts.toSet().size)
        assertNotEquals(texts[0], texts[1])
    }

    @Test
    fun yukiThanksWithTheRealHelpCountOrJustGreetsWhenThereWasNone() {
        for (seed in 1L..60L) {
            val withHelp = run(request(ChatEvent.YukiReturn(6), listOf("yuki"), seed = seed))
            assertEquals("yuki", withHelp.first().speaker)
            assertTrue(withHelp.first().text.contains("6回"), withHelp.first().text)
            val none = run(request(ChatEvent.YukiReturn(0), listOf("yuki"), seed = seed))
            assertFalse(none.first().text.contains("回") && none.first().text.contains("ヘルプ"), none.first().text)
        }
        assertEquals(ChatKeys.YUKI_RETURN_NONE, ChatEvent.YukiReturn(0).key)
        assertEquals(ChatKeys.YUKI_RETURN, ChatEvent.YukiReturn(2).key)
    }
}
