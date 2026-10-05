package com.yashikota.omaigenzo.data

object PreviewSizing {
    fun sampleSize(width: Int, height: Int, targetMaxDimension: Int): Int {
        if (targetMaxDimension == Int.MAX_VALUE || width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > targetMaxDimension) sample *= 2
        return sample
    }

    /** ARGB_8888 bytes of a 4:3 image whose long edge is [targetMaxDimension]. */
    fun estimateBytes(targetMaxDimension: Int): Long {
        val long = targetMaxDimension.toLong()
        return long * (long * 3 / 4) * 4
    }
}
