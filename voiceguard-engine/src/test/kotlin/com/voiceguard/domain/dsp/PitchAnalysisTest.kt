package com.voiceguard.domain.dsp

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("PitchAnalysis — autocorrelation pitch + perturbation")
class PitchAnalysisTest {

    /** Harmonic-rich tone at [f0] Hz (fundamental + two harmonics) for an unambiguous period. */
    private fun harmonic(f0: Double, sampleRate: Int, n: Int = sampleRate / 2): FloatArray =
        FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            (sin(2 * PI * f0 * t) + 0.5 * sin(2 * PI * 2 * f0 * t) + 0.33 * sin(2 * PI * 3 * f0 * t)).toFloat()
        }

    @Test
    fun `estimates fundamental frequency of a periodic signal`() {
        val est = PitchAnalysis.estimatePitch(harmonic(200.0, 16_000), 16_000)
        assertTrue(est.isVoiced, "Periodic tone must be detected as voiced")
        assertTrue(est.voicingStrength > 0.8f, "Voicing strength must be high, got ${est.voicingStrength}")
        assertEquals(200.0, est.f0Hz.toDouble(), 20.0, "F0 must be ~200 Hz, got ${est.f0Hz}")
    }

    @Test
    fun `silence is reported as unvoiced`() {
        val est = PitchAnalysis.estimatePitch(FloatArray(8_000) { 0.0f }, 16_000)
        assertTrue(!est.isVoiced, "Silence must be unvoiced")
    }

    @Test
    fun `f0 estimate is invariant to sample rate`() {
        val at16 = PitchAnalysis.estimatePitch(harmonic(180.0, 16_000), 16_000)
        val at24 = PitchAnalysis.estimatePitch(harmonic(180.0, 24_000), 24_000)
        assertEquals(at16.f0Hz.toDouble(), at24.f0Hz.toDouble(), 15.0,
            "Same tone must give the same F0 regardless of sample rate")
    }

    @Test
    fun `perfectly periodic signal has near-zero jitter and shimmer`() {
        val est = PitchAnalysis.estimatePitch(harmonic(200.0, 16_000), 16_000)
        val pert = PitchAnalysis.perturbation(harmonic(200.0, 16_000), est.periodSamples)
        assertTrue(pert.cycles >= 4, "Must find several cycles, got ${pert.cycles}")
        assertTrue(pert.jitter < 0.05f, "Regular signal must have low jitter, got ${pert.jitter}")
        assertTrue(pert.shimmer < 0.10f, "Regular signal must have low shimmer, got ${pert.shimmer}")
    }
}
