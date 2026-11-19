package dev.heyari.ari.llm

import dev.heyari.ari.llm.LlmModelRegistry.LARGE
import dev.heyari.ari.llm.LlmModelRegistry.MEDIUM
import dev.heyari.ari.llm.LlmModelRegistry.SMALL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMemoryTest {

    private val gib = 1L shl 30

    @Test
    fun reportedMemoryRoundsUpToTheSizeOnTheBox() {
        // The Pixel 10 Pro Fold's MemTotal: a 16 GB phone reporting 14.5 GB.
        assertEquals(16, PhoneMemory.soldGb(15_153_256L * 1024))
        assertEquals(8, PhoneMemory.soldGb(7_200_000_000L))
        assertEquals(6, PhoneMemory.soldGb(5_600_000_000L))
        assertEquals(12, PhoneMemory.soldGb(12 * gib))
        assertEquals(64, PhoneMemory.soldGb(63 * gib + 1))
        assertNull(PhoneMemory.soldGb(0))
    }

    // The figures below changed with LiteRT-LM (ari-android#52): it needs
    // 3.1 GB for Large where llama.cpp needed 6.1, so Large now fits the 8 GB
    // Galaxy S22 that ari-android#42 found it crowding, and Medium fits 6 GB.

    @Test
    fun eightGbPhoneLikeTheTestersS22() {
        assertEquals(MemoryFit.Fits, PhoneMemory.fit(SMALL, 8))
        assertEquals(MemoryFit.Fits, PhoneMemory.fit(MEDIUM, 8))
        assertEquals(MemoryFit.Fits, PhoneMemory.fit(LARGE, 8))
        assertEquals(LARGE, PhoneMemory.recommended(LlmModelRegistry.all, 8))
    }

    @Test
    fun sixGbPhoneGetsMediumNotTheHungrierSmall() {
        // Small needs more memory than Medium on LiteRT-LM; the
        // recommendation goes by tier.
        assertEquals(MemoryFit.Fits, PhoneMemory.fit(SMALL, 6))
        assertEquals(MemoryFit.Fits, PhoneMemory.fit(MEDIUM, 6))
        assertEquals(MemoryFit.Tight, PhoneMemory.fit(LARGE, 6))
        assertEquals(MEDIUM, PhoneMemory.recommended(LlmModelRegistry.all, 6))
    }

    @Test
    fun smallerPhones() {
        assertEquals(MemoryFit.Tight, PhoneMemory.fit(SMALL, 4))
        assertEquals(MemoryFit.Tight, PhoneMemory.fit(MEDIUM, 4))
        assertEquals(MemoryFit.TooBig, PhoneMemory.fit(LARGE, 4))
        assertEquals(MemoryFit.TooBig, PhoneMemory.fit(SMALL, 3))
        assertNull(PhoneMemory.recommended(LlmModelRegistry.all, 4))
    }

    @Test
    fun bigPhonesFitEverything() {
        for (model in LlmModelRegistry.all) assertEquals(MemoryFit.Fits, PhoneMemory.fit(model, 12))
        assertEquals(LARGE, PhoneMemory.recommended(LlmModelRegistry.all, 16))
    }

    @Test
    fun minimumIsTheSmallestPhoneItIsNotTooBigFor() {
        assertEquals(4, PhoneMemory.minimumGb(SMALL))
        assertEquals(4, PhoneMemory.minimumGb(MEDIUM))
        assertEquals(6, PhoneMemory.minimumGb(LARGE))
    }
}
