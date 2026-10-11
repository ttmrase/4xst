package com.ttmrase.crownfall.ai.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponseParserPlainTest {
    private fun ja(
        text: String,
        prompt: String = "",
        previous: List<String> = emptyList(),
    ) = ResponseParser.plain(text, "ja", prompt, previous)

    @Test
    fun aPlainLineIsKept() {
        assertEquals("みんなお疲れさん！", ja("みんなお疲れさん！"))
    }

    @Test
    fun onlyTheFirstNonEmptyLineAndAtMostTwoSentencesAreTaken() {
        assertEquals("おはよう。今日もがんばろう。", ja("\n\nおはよう。今日もがんばろう。三文目はいらない。\n二行目もいらない"))
        assertEquals("やったぜ！！ すごい！", ja("やったぜ！！ すごい！ また！"))
    }

    @Test
    fun namePrefixesQuotesAndMarkdownAreStripped() {
        assertEquals("ありがとなぁ", ja("Kaede: ありがとなぁ"))
        assertEquals("ありがとなぁ", ja("Kaede：ありがとなぁ"))
        assertEquals("ありがとなぁ", ja("ぽんず : ありがとなぁ"))
        assertEquals("ありがとなぁ", ja("> ありがとなぁ"))
        assertEquals("ありがとなぁ", ja("\"ありがとなぁ\""))
        assertEquals("ありがとなぁ", ja("「ありがとなぁ」"))
        assertEquals("ありがとなぁ", ja("**ありがとなぁ**"))
        assertEquals("ありがとなぁ", ja("- ありがとなぁ"))
        assertEquals("ありがとなぁ", ja("```\nありがとなぁ\n```"))
    }

    @Test
    fun jsonGarbageFails() {
        assertNull(ja("""{"lines":[{"speaker":"kaede","text":"こんにちは"}]}"""))
        assertNull(ja("こんにちは } 閉じ"))
        assertNull(ja("speaker は kaede です"))
    }

    @Test
    fun aReplyWithoutTheLocalesLettersFails() {
        assertNull(ja("Hello everyone, good morning!"))
        assertNull(ja("12345 !!!"))
        assertEquals("Good morning, everyone!", ResponseParser.plain("Good morning, everyone!", "en"))
        assertNull(ResponseParser.plain("おはようございます", "en"))
    }

    @Test
    fun tooShortOrTooLongFails() {
        assertNull(ja("あ"))
        assertNull(ja(""))
        assertNull(ja("   \n  "))
        assertEquals("はい", ja("はい"))
        assertNull(ja("あ".repeat(121)))
        assertEquals(120, ja("あ".repeat(120))!!.length)
    }

    @Test
    fun anEchoOfThePromptFails() {
        val prompt = "あなたは「Kaede」。スマホの戦略ゲームの同盟チャットで話している。\nKaede の返事:"
        assertNull(ja("スマホの戦略ゲームの同盟チャットで話している。", prompt))
        assertEquals("お疲れさん、今日もええ天気やな", ja("お疲れさん、今日もええ天気やな", prompt))
    }

    @Test
    fun aRepeatOfThePreviousLineFails() {
        val prev = listOf("おはようさん！今日もええ感じやで")
        assertNull(ja("おはようさん！今日もええ感じやで", previous = prev))
        assertNull(ja("おはようさん、今日もええ感じやで！", previous = prev), "punctuation and spacing do not matter")
        assertEquals("ほな、また後でな", ja("ほな、また後でな", previous = prev))
    }
}
