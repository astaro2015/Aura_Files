package com.aurafiles.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashTest {
    @Test fun similarityIsStableForHashVectors() {
        val a = PerceptualHash.Fingerprint(0x0123456789ABCDEFL, 0x0F0F0F0F0F0F0F0FL)
        assertEquals(1.0, PerceptualHash.similarity(a, a), 0.0)
        val veryDifferent = PerceptualHash.Fingerprint(a.dHash.inv(), a.pHash.inv())
        assertTrue(PerceptualHash.similarity(a, veryDifferent) < 0.1)
    }
}
