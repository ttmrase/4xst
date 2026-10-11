package com.ttmrase.crownfall.ai.providers

import kotlin.test.Test
import kotlin.test.assertEquals

class OnDeviceProfileTest {
    @Test
    fun theModelFileNameDecidesTheProfileCaseInsensitively() {
        for (name in listOf("gemma-3n-E2B-it-int4.litertlm", "gemma-3n-e4b-it.task", "GEMMA-3N-E2B.LITERTLM")) {
            assertEquals(OnDeviceProfile.STANDARD, OnDeviceProfile.detect(name), name)
        }
        for (name in listOf(
            "gemma3-1b-it-int4.task",
            "Gemma3-1B-IT_q4.litertlm",
            "gemma-3-270m-it.task",
            "model_q8_1b.task",
        )) {
            assertEquals(OnDeviceProfile.TINY, OnDeviceProfile.detect(name), name)
        }
    }

    @Test
    fun anUnknownOrMissingNameIsStandard() {
        assertEquals(OnDeviceProfile.STANDARD, OnDeviceProfile.detect("something.litertlm"))
        assertEquals(OnDeviceProfile.STANDARD, OnDeviceProfile.detect(null))
        assertEquals(OnDeviceProfile.STANDARD, OnDeviceProfile.detect("gemma-21b.task"), "21b is not 1b")
    }

    @Test
    fun theSettingOverridesTheDetection() {
        assertEquals(OnDeviceProfile.TINY, OnDeviceProfile.resolve("tiny", "gemma-3n-e2b.litertlm"))
        assertEquals(OnDeviceProfile.STANDARD, OnDeviceProfile.resolve("standard", "gemma3-1b.task"))
        assertEquals(OnDeviceProfile.TINY, OnDeviceProfile.resolve(OnDeviceProfile.AUTO, "gemma3-1b.task"))
        assertEquals(OnDeviceProfile.STANDARD, OnDeviceProfile.resolve("garbage", null))
    }

    @Test
    fun theSamplerAndLimitsFollowTheDirectorsTable() {
        val s = OnDeviceProfile.STANDARD
        assertEquals(listOf(3, 160, 40, 96), listOf(s.maxSentences, s.maxChars, s.topK, s.maxTokens))
        assertEquals(listOf(0.9, 0.95), listOf(s.temperature, s.topP))
        val t = OnDeviceProfile.TINY
        assertEquals(listOf(2, 120), listOf(t.maxSentences, t.maxChars))
    }
}
