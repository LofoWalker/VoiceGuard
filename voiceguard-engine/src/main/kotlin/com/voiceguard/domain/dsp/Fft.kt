package com.voiceguard.domain.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place radix-2 Cooley-Tukey FFT — pure JVM, no native dependency (ADR-01).
 *
 * Shared by the spectral adapter and the cepstral rule so there is a single FFT implementation
 * in the codebase. All entry points require power-of-two lengths.
 */
object Fft {

    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    /** Smallest power of two ≥ [n] (with [n] ≥ 1). */
    fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /**
     * In-place complex transform of [re]/[im] (equal power-of-two lengths).
     * Forward when [inverse] is false; inverse additionally scales every sample by 1/N.
     */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size) { "re and im must have equal length" }
        require(isPowerOfTwo(n)) { "length must be a power of two, got $n" }

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        val dir = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len / 2
            val wRe = cos(dir * 2.0 * PI / len).toFloat()
            val wIm = sin(dir * 2.0 * PI / len).toFloat()
            var k = 0
            while (k < n) {
                var tRe = 1.0f; var tIm = 0.0f
                for (m in 0 until half) {
                    val uRe = re[k + m];          val uIm = im[k + m]
                    val vRe = re[k + m + half] * tRe - im[k + m + half] * tIm
                    val vIm = re[k + m + half] * tIm + im[k + m + half] * tRe
                    re[k + m] = uRe + vRe;        im[k + m] = uIm + vIm
                    re[k + m + half] = uRe - vRe; im[k + m + half] = uIm - vIm
                    val newTRe = tRe * wRe - tIm * wIm; tIm = tRe * wIm + tIm * wRe; tRe = newTRe
                }
                k += len
            }
            len = len shl 1
        }

        if (inverse) {
            val scale = 1.0f / n
            for (i in 0 until n) { re[i] *= scale; im[i] *= scale }
        }
    }

    /**
     * One-sided magnitude spectrum (N/2 bins) of real [signal] whose length is a power of two.
     */
    fun magnitudeSpectrum(signal: FloatArray): FloatArray {
        val re = signal.copyOf()
        val im = FloatArray(signal.size)
        transform(re, im, inverse = false)
        return FloatArray(signal.size / 2) { i ->
            sqrt((re[i] * re[i] + im[i] * im[i]).toDouble()).toFloat()
        }
    }
}
