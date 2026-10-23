package com.yashikota.omaigenzo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorScienceEdgeCaseTest {

    private fun steps(count: Int) = (0..count).map { it.toFloat() / count }

    @Test
    fun exposureStepsAreSymmetric() {
        for (ev in listOf(0.5f, 1f, 2f, 3.3f, 5f)) {
            assertEquals(1f, ColorScience.evToLinearMultiplier(ev) * ColorScience.evToLinearMultiplier(-ev), 1e-5f)
        }
    }

    @Test
    fun aDegenerateWhiteLevelNeverDividesByZero() {
        assertEquals(0f, ColorScience.normalizeBayer(512, 512f, 512f), 0f)
        assertEquals(1f, ColorScience.normalizeBayer(513, 512f, 512f), 0f)
        assertEquals(0f, ColorScience.normalizeBayer(100, 512f, 300f), 0f)
    }

    @Test
    fun normalizedBayerValuesAreLinearBetweenBlackAndWhite() {
        assertEquals(0.5f, ColorScience.normalizeBayer(1_024, 0f, 2_048f), 1e-6f)
        assertEquals(0.25f, ColorScience.normalizeBayer(1_536, 1_024f, 3_072f), 1e-6f)
    }

    @Test
    fun srgbGammaClampsOutOfRangeInput() {
        assertEquals(0f, ColorScience.srgbGamma(-0.5f), 0f)
        assertEquals(1f, ColorScience.srgbGamma(4f), 1e-6f)
    }

    @Test
    fun srgbGammaIsContinuousAtTheLinearKnee() {
        val knee = 0.0031308f
        assertEquals(ColorScience.srgbGamma(knee), ColorScience.srgbGamma(knee + 1e-6f), 1e-4f)
    }

    @Test
    fun srgbGammaIsMonotonicAndBrightensMidtones() {
        val values = steps(1_000).map(ColorScience::srgbGamma)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(ColorScience.srgbGamma(0.18f) > 0.18f)
    }

    @Test
    fun acesMapsBlackToBlackAndSaturatesHighlights() {
        val (r, _, _) = ColorScience.acesToneMap(0f, 0f, 0f)
        assertEquals(0f, r, 1e-6f)

        val (bright, _, _) = ColorScience.acesToneMap(100f, 0f, 0f)
        val (brighter, _, _) = ColorScience.acesToneMap(1_000f, 0f, 0f)
        assertEquals("input is clamped at 100", bright, brighter, 0f)
        assertEquals(1f, bright, 1e-2f)
    }

    @Test
    fun acesTreatsNegativeInputAsBlack() {
        val (r, g, b) = ColorScience.acesToneMap(-1f, -10f, -0.001f)
        assertEquals(0f, r, 1e-6f)
        assertEquals(0f, g, 1e-6f)
        assertEquals(0f, b, 1e-6f)
    }

    @Test
    fun acesIsMonotonicPerChannel() {
        val values = steps(500).map { ColorScience.acesToneMap(it * 16f, 0f, 0f).first }
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun referenceValuesMatchTheNativePipeline() {
        // The same numbers are asserted against ColorScience.h in native_tests.cpp.
        assertEquals(0.461356f, ColorScience.srgbGamma(0.18f), 1e-4f)
        assertEquals(0.735357f, ColorScience.srgbGamma(0.5f), 1e-4f)
        val (r, g, b) = ColorScience.acesToneMap(0.18f, 1f, 4f)
        assertEquals(0.553458f, r, 1e-4f)
        assertEquals(0.908230f, g, 1e-4f)
        assertEquals(0.988223f, b, 1e-4f)
    }

    @Test
    fun acesChannelsAreIndependent() {
        val mixed = ColorScience.acesToneMap(0.2f, 0.5f, 2f)
        assertEquals(ColorScience.acesToneMap(0.2f, 0.2f, 0.2f).first, mixed.first, 0f)
        assertEquals(ColorScience.acesToneMap(0.5f, 0.5f, 0.5f).second, mixed.second, 0f)
        assertEquals(ColorScience.acesToneMap(2f, 2f, 2f).third, mixed.third, 0f)
    }
}
