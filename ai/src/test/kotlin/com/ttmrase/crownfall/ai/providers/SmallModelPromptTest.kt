package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatTurn
import com.ttmrase.crownfall.ai.SpokenLine
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatVars
import com.ttmrase.crownfall.core.chat.PromptContextBuilder
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmallModelPromptTest {
    private val data = ClasspathDataSource.load()
    private val ids = data.personas.personas.map { it.id }

    private fun request(
        speakers: List<String> = listOf("kaede"),
        locale: String = "ja",
        event: ChatEvent = ChatEvent.DailyGreeting,
    ) = ChatRequest(
        system = "長い脚本家プロンプト".repeat(200),
        messages =
            listOf(
                ChatTurn("ponzu", "こんにちは！"),
                ChatTurn("player", "やあ"),
                ChatTurn("johnny", "ガチャ回した！"),
                ChatTurn("gen", "焦るな"),
                ChatTurn("sato", "採集は順調です"),
                ChatTurn("system", "（内部のきっかけ）kaede が話す"),
            ),
        event = event,
        speakers = speakers,
        seed = 1,
        vars = ChatVars(emptyMap(), emptyMap(), "領主さん"),
        personas = speakers.mapNotNull { data.personas.persona(it) }.map { PromptContextBuilder.brief(it, "アレンはん") },
        names = data.personas.personas.associate { it.id to it.displayName } + ("player" to "アレン"),
        situation = "プレイヤーが今日最初にログインした。挨拶する。二文目は出さない。",
        locale = locale,
    )

    @Test
    fun theSystemInstructionStaysWithinFourHundredCharsForEveryPersonaAndLocale() {
        for (id in ids) {
            for (locale in listOf("ja", "en")) {
                val built = SmallModelPrompt.build(request(listOf(id), locale), id)
                assertTrue(
                    built.system.length <= SmallModelPrompt.MAX_SYSTEM_CHARS,
                    "$id/$locale ${built.system.length}",
                )
            }
        }
    }

    @Test
    fun itDescribesOnePersonaWithNameCallNameAndExamples() {
        val built = SmallModelPrompt.build(request(listOf("kaede")), "kaede")
        val kaede = data.personas.persona("kaede")!!
        assertTrue("あなたは「Kaede」" in built.system)
        assertTrue("「アレンはん」" in built.system)
        assertTrue("同盟チャット" in built.system)
        assertTrue("口調の例" in built.system)
        assertTrue(kaede.speechStyle.samples.count { it in built.system } in 1..SmallModelPrompt.MAX_EXAMPLES)
        assertTrue("セリフだけ" in built.system && "JSON" in built.system)
        // 1 人だけ。ほかのペルソナの名前は入らない
        assertTrue(ids.filter { it != "kaede" }.all { id -> data.personas.persona(id)!!.displayName !in built.system })
    }

    @Test
    fun theUserMessageHasTheSituationRecentLinesByDisplayNameAndTheClosingCue() {
        val built = SmallModelPrompt.build(request(), "kaede")
        val lines = built.prompt.lines()
        assertEquals("状況: プレイヤーが今日最初にログインした。", lines.first())
        assertEquals("Kaede の返事:", lines.last())
        val history = lines.filter { ": " in it && !it.startsWith("状況") }
        assertEquals(SmallModelPrompt.MAX_HISTORY, history.size)
        assertEquals(
            listOf("アレン: やあ", "ジョニー: ガチャ回した！", "老兵ゲン: 焦るな", "Sato_Farm: 採集は順調です").map {
                it.replace("Sato_Farm", data.personas.persona("sato")!!.displayName)
            },
            history,
        )
    }

    @Test
    fun internalIdsAreNeverShownToTheModel() {
        val built = SmallModelPrompt.build(request(listOf("kaede", "gen")), "gen", listOf(SpokenLine("kaede", "やっほー")))
        val text = built.system + "\n" + built.prompt
        for (id in ids) {
            assertFalse(Regex("(?<![A-Za-z0-9_])$id(?![A-Za-z0-9_])").containsMatchIn(text), "id $id leaked: $text")
        }
        assertFalse("system:" in text || "player:" in text)
        assertTrue("Kaede: やっほー" in built.prompt)
    }

    @Test
    fun theSituationIsSkippedForFreeTextAndTheEnglishPromptIsInEnglish() {
        val free = SmallModelPrompt.build(request(event = ChatEvent.PlayerFreeText("やあ", "alliance")), "kaede")
        assertFalse("状況" in free.prompt)
        val en = SmallModelPrompt.build(request(locale = "en"), "kaede")
        assertTrue("Reply in English" in en.system, en.system)
        assertTrue("\"Kaede\"" in en.system && "アレンはん" in en.system)
        assertTrue(en.prompt.endsWith("Kaede's reply:"))
        assertFalse("状況" in en.prompt)
        // 日本語の口調の説明や例文は入れない（英語の返事を日本語に引っ張らない）
        assertFalse(Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]").containsMatchIn(en.system.replace("アレンはん", "")))
    }

    @Test
    fun theTestRequestAsksKaedeToGreet() {
        val r = SmallModelPrompt.testRequest(data, "ja")
        assertEquals(listOf("kaede"), r.speakers)
        val built = SmallModelPrompt.build(r, "kaede")
        assertTrue("あなたは「Kaede」" in built.system && built.prompt.endsWith("Kaede の返事:"))
    }
}
