package com.ttmrase.crownfall.ai.ondevice

import com.ttmrase.crownfall.ai.ondevice.ModelTransfer.Advice
import com.ttmrase.crownfall.ai.ondevice.ModelTransfer.Resume
import com.ttmrase.crownfall.ai.ondevice.ModelTransfer.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTransferTest {
    @Test
    fun supportedNamesAndSafeFileNames() {
        assertTrue(ModelTransfer.isSupportedName("gemma.LiteRTLM"))
        assertTrue(ModelTransfer.isSupportedName("m.task"))
        assertFalse(ModelTransfer.isSupportedName("m.bin"))
        assertEquals("gemma-3n.litertlm", ModelTransfer.safeFileName("/storage/../gemma-3n.litertlm"))
        assertEquals("a.task", ModelTransfer.safeFileName("..\\\\.a.task"))
        assertNull(ModelTransfer.safeFileName("evil/../"))
        assertNull(ModelTransfer.safeFileName("readme.txt"))
        assertNull(ModelTransfer.safeFileName(""))
    }

    @Test
    fun onlyPlainHttpsUrlsAreAccepted() {
        assertNull(ModelTransfer.validateUrl("https://example.com/m.litertlm"))
        assertEquals("https_only", ModelTransfer.validateUrl("http://example.com/m.litertlm"))
        assertEquals("https_only", ModelTransfer.validateUrl("file:///sdcard/m.task"))
        assertEquals("userinfo", ModelTransfer.validateUrl("https://user:pw@example.com/m.task"))
        assertEquals("no_host", ModelTransfer.validateUrl("https:///m.task"))
        assertEquals("bad_url", ModelTransfer.validateUrl("https://exa mple.com/m.task"))
    }

    @Test
    fun freeSpaceMustCoverTheModelPlusMargin() {
        val gb = 1024L * 1024 * 1024
        assertTrue(ModelTransfer.hasEnoughSpace(3 * gb, 2 * gb))
        assertFalse(ModelTransfer.hasEnoughSpace(2 * gb, 2 * gb))
        assertFalse(ModelTransfer.hasEnoughSpace(2 * gb + 63L * 1024 * 1024, 2 * gb))
        assertTrue(ModelTransfer.hasEnoughSpace(ModelTransfer.STORAGE_MARGIN_BYTES, 0))
        assertTrue(ModelTransfer.hasEnoughSpace(ModelTransfer.STORAGE_MARGIN_BYTES, -5))
    }

    @Test
    fun resumeFollowsTheServerAnswer() {
        assertEquals(Resume.Restart, ModelTransfer.resumeFor(0, 206, "bytes 0-9/10"))
        assertEquals(Resume.From(100), ModelTransfer.resumeFor(100, 206, "bytes 100-199/200"))
        assertEquals(Resume.From(100), ModelTransfer.resumeFor(100, 206, "bytes 100-199/*"))
        assertEquals(Resume.Restart, ModelTransfer.resumeFor(100, 206, "bytes 0-199/200"))
        assertEquals(Resume.Restart, ModelTransfer.resumeFor(100, 206, null))
        assertEquals(Resume.Restart, ModelTransfer.resumeFor(100, 200, null))
        assertEquals(Resume.Complete, ModelTransfer.resumeFor(200, 416, "bytes */200"))
        assertEquals(Resume.Restart, ModelTransfer.resumeFor(250, 416, "bytes */200"))
        assertEquals(Resume.Restart, ModelTransfer.resumeFor(100, 500, null))
    }

    @Test
    fun verifyChecksSizeAndOptionalSha() {
        val sha = "AB:CD ef01"
        assertEquals(Verdict.OK, ModelTransfer.verify(10, 10, "", "whatever"))
        assertEquals(Verdict.OK, ModelTransfer.verify(0, 5, "", ""))
        assertEquals(Verdict.SIZE_MISMATCH, ModelTransfer.verify(10, 9, "", ""))
        assertEquals(Verdict.OK, ModelTransfer.verify(10, 10, sha, "abcdef01"))
        assertEquals(Verdict.SHA_MISMATCH, ModelTransfer.verify(10, 10, sha, "abcdef02"))
        assertEquals(Verdict.SIZE_MISMATCH, ModelTransfer.verify(10, 11, sha, "abcdef01"))
    }

    @Test
    fun deviceAdviceIsAWarningNotABlock() {
        val gb = 1024L * 1024 * 1024
        assertTrue(ModelTransfer.assess(8 * gb, 10 * gb, 2 * gb).isEmpty())
        assertTrue(Advice.LOW_RAM in ModelTransfer.assess(4 * gb, 10 * gb, 1 * gb))
        assertTrue(Advice.LOW_STORAGE in ModelTransfer.assess(8 * gb, 1 * gb, 2 * gb))
        assertTrue(Advice.MODEL_BIGGER_THAN_RAM_SHARE in ModelTransfer.assess(4 * gb, 10 * gb, 3 * gb))
        assertTrue(ModelTransfer.assess(0, 10 * gb, 1 * gb).isEmpty())
    }
}
