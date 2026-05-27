package com.voiceguard.domain.dsp

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Fft — shared radix-2 FFT")
class FftTest {

    @Test
    fun `forward then inverse recovers the original signal`() {
        val n = 256
        val original = FloatArray(n) { i -> sin(2 * PI * 5 * i / n).toFloat() + 0.3f * sin(2 * PI * 17 * i / n).toFloat() }
        val re = original.copyOf()
        val im = FloatArray(n)

        Fft.transform(re, im, inverse = false)
        Fft.transform(re, im, inverse = true)

        for (i in 0 until n) {
            assertEquals(original[i].toDouble(), re[i].toDouble(), 1e-3, "Sample $i must round-trip")
        }
    }

    @Test
    fun `magnitude spectrum peaks at the tone's bin`() {
        val n = 512
        val bin = 20
        val signal = FloatArray(n) { i -> sin(2 * PI * bin * i / n).toFloat() }

        val mag = Fft.magnitudeSpectrum(signal)

        val peakBin = mag.indices.maxByOrNull { mag[it] }!!
        assertEquals(bin, peakBin, "Spectral peak must sit at the tone's bin")
        assertEquals(n / 2, mag.size, "One-sided spectrum has N/2 bins")
    }

    @Test
    fun `nextPowerOfTwo and isPowerOfTwo behave correctly`() {
        assertEquals(8, Fft.nextPowerOfTwo(5))
        assertEquals(4096, Fft.nextPowerOfTwo(4096))
        assertTrue(Fft.isPowerOfTwo(1024))
        assertTrue(!Fft.isPowerOfTwo(1000))
    }
}
