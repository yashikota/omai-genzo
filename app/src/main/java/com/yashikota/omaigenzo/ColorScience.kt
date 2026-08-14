package com.yashikota.omaigenzo

import kotlin.math.pow

object ColorScience {

    fun evToLinearMultiplier(ev: Float): Float = 2.0f.pow(ev)

    fun normalizeBayer(rawVal: Int, blackLevel: Float, whiteLevel: Float): Float {
        val denom = (whiteLevel - blackLevel).coerceAtLeast(1.0f)
        val value = (rawVal.toFloat() - blackLevel) / denom
        return value.coerceIn(0.0f, 1.0f)
    }

    fun srgbGamma(linearVal: Float): Float {
        val v = linearVal.coerceIn(0.0f, 1.0f)
        return if (v <= 0.0031308f) {
            v * 12.92f
        } else {
            1.055f * v.pow(1.0f / 2.4f) - 0.055f
        }
    }

    fun acesToneMap(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val a = 2.51f
        val bConst = 0.03f
        val c = 2.43f
        val d = 0.59f
        val e = 0.14f

        fun applyAces(x: Float): Float {
            val clamped = x.coerceIn(0.0f, 100.0f)
            val mapped = ((clamped * (a * clamped + bConst)) / (clamped * (c * clamped + d) + e)).coerceIn(0.0f, 1.0f)
            return srgbGamma(mapped)
        }

        return Triple(applyAces(r), applyAces(g), applyAces(b))
    }
}
