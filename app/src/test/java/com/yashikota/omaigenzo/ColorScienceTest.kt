package com.yashikota.omaigenzo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ColorScienceTest {

    @Test
    fun testEvToLinearMultiplier() {
        assertEquals(1.0f, ColorScience.evToLinearMultiplier(0.0f), 0.0001f)
        assertEquals(2.0f, ColorScience.evToLinearMultiplier(1.0f), 0.0001f)
        assertEquals(0.5f, ColorScience.evToLinearMultiplier(-1.0f), 0.0001f)
        assertEquals(4.0f, ColorScience.evToLinearMultiplier(2.0f), 0.0001f)
        assertEquals(2.0f.pow(2.5f), ColorScience.evToLinearMultiplier(2.5f), 0.0001f)
    }

    @Test
    fun testNormalizeBayerClamping() {
        // Exact black level
        assertEquals(0.0f, ColorScience.normalizeBayer(512, 512.0f, 16383.0f), 0.0001f)
        // Exact white level
        assertEquals(1.0f, ColorScience.normalizeBayer(16383, 512.0f, 16383.0f), 0.0001f)
        // Below black level (clamped to 0)
        assertEquals(0.0f, ColorScience.normalizeBayer(100, 512.0f, 16383.0f), 0.0001f)
        // Above white level (clamped to 1)
        assertEquals(1.0f, ColorScience.normalizeBayer(20000, 512.0f, 16383.0f), 0.0001f)
    }

    @Test
    fun testSrgbGammaTransfer() {
        assertEquals(0.0f, ColorScience.srgbGamma(0.0f), 0.0001f)
        assertEquals(1.0f, ColorScience.srgbGamma(1.0f), 0.0001f)

        // Linear segment test (x <= 0.0031308)
        val linearVal = 0.002f
        assertEquals(linearVal * 12.92f, ColorScience.srgbGamma(linearVal), 0.0001f)

        // Exponential segment test (x > 0.0031308)
        val midVal = 0.5f
        val expected = 1.055f * midVal.pow(1.0f / 2.4f) - 0.055f
        assertEquals(expected, ColorScience.srgbGamma(midVal), 0.0001f)
    }

    @Test
    fun testAcesToneMapOutputRange() {
        val (r0, g0, b0) = ColorScience.acesToneMap(0.0f, 0.0f, 0.0f)
        assertTrue(r0 in 0.0f..1.0f)
        assertTrue(g0 in 0.0f..1.0f)
        assertTrue(b0 in 0.0f..1.0f)

        val (r1, g1, b1) = ColorScience.acesToneMap(1.0f, 1.0f, 1.0f)
        assertTrue(r1 in 0.0f..1.0f)
        assertTrue(g1 in 0.0f..1.0f)
        assertTrue(b1 in 0.0f..1.0f)

        // HDR over-bright inputs
        val (rHdr, gHdr, bHdr) = ColorScience.acesToneMap(10.0f, 50.0f, 100.0f)
        assertTrue(rHdr in 0.0f..1.0f)
        assertTrue(gHdr in 0.0f..1.0f)
        assertTrue(bHdr in 0.0f..1.0f)
    }
}
