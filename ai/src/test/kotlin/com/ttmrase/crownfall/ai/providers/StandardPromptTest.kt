package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatTurn
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatVars
import com.ttmrase.crownfall.core.chat.PromptContextBuilder
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandardPromptTest {
    private val data = ClasspathDataSource.load()
    private val ids = data.personas.personas.map { it.id }

    private fun request(
        speakers: List<String> = listOf("kaede"),
        locale: String = "ja",
        event: ChatEvent = ChatEvent.DailyGreeting,
        history: Int = 12,
        lineLength: Int = 12,
    ) = ChatRequest(
        system = "長い脚本家プロンプト".repeat(200),
        messages =
            (1..history).map {
                ChatTurn(if (it % 2 == 0) "player" else ids[it % ids.size], "発言$it" + "あ".repeat(lineLength))
            } +
                ChatTurn("system", "（内部のきっかけ）kaede が話す"),
        event = event,
        speakers = speakers,
        seed = 1,
        vars = ChatVars(emptyMap(), emptyMap(), "領主さん"),
        personas =
            speakers.mapNotNull {
                data.personas.persona(it)
            }.map { PromptContextBuilder.brief(it, "アレンはん", 2, "Allen") },
        names = data.personas.personas.associate { it.id to it.displayName } + ("player" to "アレン"),
        situation = "プレイヤーが今日最初にログインした。挨拶する。三つ目の文。四つ目の文は出さない。",
        locale = locale,
        allianceLine = PromptContextBuilder.worldLine(data),
        status = "城Lv5。木材が不足。直近: 襲撃を撃退した。",
        fewShot = mapOf("kaede" to listOf("みんなお疲れさん！", "今日もええ感じやで")),
    )

    @Test
    fun theSystemInstructionIsFourToSevenHundredCharsForEveryPersona() {
        for (id in ids) {
            val built = StandardPrompt.single(request(listOf(id)), id)
            assertTrue(
                built.system.length in StandardPrompt.MIN_SYSTEM_CHARS..StandardPrompt.MAX_SYSTEM_CHARS,
                "$id ${built.system.length}",
            )
        }
    }

    @Test
    fun theSystemInstructionDescribesThePersonaTheRelationshipTheWorldAndTheStyle() {
        val built = StandardPrompt.single(request(), "kaede")
        val system = built.system
        assertTrue("あなたは「Kaede」として、スマホの戦略ゲーム「Crownfall」の同盟チャットで会話します。" in system)
        assertTrue("R5の同盟のリーダー" in system, system)
        assertTrue("社交的" in system && "面倒見がよい" in system, system)
        assertTrue("「アレンはん」" in system && "親しい" in system, system)
        assertTrue(data.personas.allianceName in system && "30" in system)
        assertTrue("食料・木材・石・金" in system && "ラリー" in system && "野蛮人" in system)
        assertTrue("1〜3文" in system && "質問を返したり" in system && "毎回ゲームの状況に触れなくてよい" in system)
        assertTrue("セリフだけを書く。名前・ラベル・記号は付けない。" in system)
        val examples = data.personas.persona("kaede")!!.speechStyle.samples.count { "「$it」" in system }
        assertTrue(examples in 3..5, "examples $examples")
    }

    @Test
    fun theUserMessageHasExamplesHistoryByDisplayNameTheStageDirectionAndTheClosingCue() {
        val built = StandardPrompt.single(request(), "kaede")
        val lines = built.prompt.lines()
        assertEquals("例: 「みんなお疲れさん！」「今日もええ感じやで」", lines.first())
        assertEquals("Kaede の返事:", lines.last())
        val direction = lines[lines.size - 2]
        assertTrue(direction.startsWith("（状況: ") && "Kaede として" in direction, direction)
        // きっかけの文は 3 文まで、状況の材料（城 Lv・資源・できごと）が付く
        assertTrue(
            "三つ目の文。" in direction && "四つ目" !in direction && "城Lv5。" in direction && "木材が不足" in direction,
            direction,
        )
        val history = lines.filter { Regex("^\\S+: 発言").containsMatchIn(it) }
        assertEquals(StandardPrompt.MAX_HISTORY, history.size)
        assertTrue(history.any { it.startsWith("アレン: ") }, "the player is shown by the player's name")
    }

    @Test
    fun theHistoryIsTrimmedFromTheOldestToKeepThePromptWithinTheBudget() {
        val built = StandardPrompt.single(request(history = 20, lineLength = 110), "kaede")
        val history = built.prompt.lines().filter { Regex("^\\S+: 発言").containsMatchIn(it) }
        assertTrue(
            history.size in StandardPrompt.MIN_HISTORY until StandardPrompt.MAX_HISTORY,
            "history ${history.size}",
        )
        assertTrue(built.prompt.length <= StandardPrompt.USER_BUDGET_CHARS + 200, "prompt ${built.prompt.length}")
        assertTrue("発言20" in history.last(), "the newest lines are kept")
    }

    @Test
    fun internalIdsAreNeverShown() {
        val r = request(listOf("kaede", "gen", "yuki"))
        for (built in listOf(
            StandardPrompt.single(r, "kaede"),
            StandardPrompt.multi(r, listOf("kaede", "gen", "yuki")),
        )) {
            val text = built.system + "\n" + built.prompt
            for (id in ids) {
                assertFalse(Regex("(?<![A-Za-z0-9_])$id(?![A-Za-z0-9_])").containsMatchIn(text), "id $id leaked")
            }
            assertFalse("system:" in text || "player:" in text)
        }
    }

    @Test
    fun theMultiSpeakerPromptAsksForNameColonLineForUpToThreeSpeakers() {
        val r = request(listOf("kaede", "gen", "yuki", "lin"))
        val built = StandardPrompt.multi(r, listOf("kaede", "gen", "yuki", "lin"))
        assertTrue(built.system.length <= StandardPrompt.MAX_SYSTEM_CHARS, "system ${built.system.length}")
        for (name in listOf("Kaede", "老兵ゲン", "Yuki")) assertTrue("・$name" in built.system, name)
        assertFalse("Lin" in built.system, "at most three speakers")
        assertTrue("「表示名: セリフ」" in built.system)
        assertTrue(built.prompt.lines().last().startsWith("Kaede、老兵ゲン、Yuki の順に"))
        assertEquals(
            mapOf("Kaede" to "kaede", "老兵ゲン" to "gen", "Yuki" to "yuki"),
            StandardPrompt.nameTable(r, listOf("kaede", "gen", "yuki", "lin")),
        )
    }

    @Test
    fun aFreeTextReplyAsksToReactToTheWordsAndSometimesAskBack() {
        val built = StandardPrompt.single(request(event = ChatEvent.PlayerFreeText("今日は寒いね", "alliance")), "kaede")
        assertTrue("具体的に反応し、ときどき質問を返す" in built.prompt)
    }

    @Test
    fun theEnglishPromptSaysTheSameInEnglish() {
        val built = StandardPrompt.single(request(locale = "en"), "kaede")
        assertTrue("You are \"Kaede\"" in built.system && "Reply in English" in built.system, built.system)
        assertTrue("close friends" in built.system && "\"Allen\"" in built.system, built.system)
        assertFalse(Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]").containsMatchIn(built.system), "no Japanese call name")
        assertTrue(built.prompt.endsWith("Kaede's reply:"))
        assertFalse("例:" in built.prompt, "Japanese examples are not shown for English")
    }

    @Test
    fun theOutputInstructionIsAlwaysLastAndNeverCutForEveryPersonaLocaleAndShape() {
        for (locale in listOf("ja", "en")) {
            val limit = if (locale == "ja") StandardPrompt.MAX_SYSTEM_CHARS else StandardPrompt.MAX_SYSTEM_CHARS_EN
            val tail =
                mapOf(
                    "ja" to ("セリフだけを書く。名前・ラベル・記号は付けない。" to "セリフ以外は書かない。"),
                    "en" to ("Reply in English." to "Write nothing else."),
                ).getValue(locale)
            for (id in ids) {
                val r = request(listOf(id, "kaede", "gen"), locale)
                val single = StandardPrompt.single(r, id).system
                val multi = StandardPrompt.multi(r, listOf(id, "kaede", "gen")).system
                assertTrue(single.endsWith(tail.first), "$locale/$id single: ${single.takeLast(50)}")
                assertTrue(multi.endsWith(tail.second), "$locale/$id multi: ${multi.takeLast(50)}")
                assertTrue(single.length <= limit, "$locale/$id single ${single.length}")
                assertTrue(multi.length <= limit, "$locale/$id multi ${multi.length}")
            }
        }
    }

    @Test
    fun theEnglishPromptDescribesTheRoleAndPersonalityInEnglish() {
        val en = StandardPrompt.single(request(locale = "en"), "kaede").system
        assertTrue("rank R5 alliance leader" in en && "sociable" in en && "caring" in en, en)
        assertFalse(Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]").containsMatchIn(en.replace("アレンはん", "")))
    }
}
