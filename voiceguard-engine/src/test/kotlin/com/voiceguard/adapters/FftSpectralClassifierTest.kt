package com.voiceguard.adapters

import com.voiceguard.domain.model.AudioChunk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertTrue

@DisplayName("FftSpectralClassifier — DSP spectral artifact detection")
class FftSpectralClassifierTest {

    private val classifier = FftSpectralClassifier()

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Full-band organic speech simulation: multi-tone signal spread across 500 Hz–7 000 Hz. */
    private fun organicChunk(sampleRate: Int = 16_000): AudioChunk {
        val samples = FloatArray(sampleRate / 2) { i ->
            val t = i.toDouble() / sampleRate
            // Mix of harmonically unrelated tones across the full audible range
            (sin(2 * PI * 500 * t) * 0.25
                    + sin(2 * PI * 1200 * t) * 0.20
                    + sin(2 * PI * 2500 * t) * 0.20
                    + sin(2 * PI * 4000 * t) * 0.15
                    + sin(2 * PI * 6500 * t) * 0.15
                    + sin(2 * PI * 7200 * t) * 0.05).toFloat()
        }
        return AudioChunk(samples, sampleRate)
    }

    /**
     * Band-limited TTS simulation: same tones but hard cutoff at 5 kHz.
     * Mimics an 8 kHz Nyquist vocoder output resampled to 16 kHz.
     */
    private fun bandLimitedChunk(sampleRate: Int = 16_000): AudioChunk {
        val samples = FloatArray(sampleRate / 2) { i ->
            val t = i.toDouble() / sampleRate
            (sin(2 * PI * 500 * t) * 0.35
                    + sin(2 * PI * 1200 * t) * 0.35
                    + sin(2 * PI * 2500 * t) * 0.30).toFloat()   // nothing above 2.5 kHz
        }
        return AudioChunk(samples, sampleRate)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────

    @Test
    fun `score for organic full-band audio is low`() {
        val score = classifier.classify(organicChunk())
        assertTrue(score < 0.5f, "Full-band organic speech must score below 0.5, got $score")
    }

    @Test
    fun `score for band-limited TTS simulation is higher than organic`() {
        val organicScore = classifier.classify(organicChunk())
        val syntheticScore = classifier.classify(bandLimitedChunk())
        assertTrue(
            syntheticScore > organicScore,
            "Band-limited TTS must score higher than organic (synthetic=$syntheticScore, organic=$organicScore)"
        )
    }

    @Test
    fun `score is always in range 0 to 1`() {
        listOf(organicChunk(), bandLimitedChunk()).forEach { chunk ->
            val score = classifier.classify(chunk)
            assertTrue(score in 0.0f..1.0f, "Score must be in [0,1], got $score")
        }
    }

    @Test
    fun `classifier is deterministic on identical input`() {
        val chunk = organicChunk()
        val score1 = classifier.classify(chunk)
        val score2 = classifier.classify(chunk)
        assertTrue(score1 == score2, "Same input must produce same score: $score1 vs $score2")
    }

    @Test
    fun `score is finite for silent chunk`() {
        val silent = AudioChunk(FloatArray(8_000) { 0.0f })
        val score = classifier.classify(silent)
        assertTrue(!score.isNaN() && !score.isInfinite(), "Silent input must not produce NaN/Inf, got $score")
        assertTrue(score in 0.0f..1.0f)
    }

    @Test
    fun `chunk shorter than fftSize is handled without exception`() {
        val shortChunk = AudioChunk(FloatArray(100) { 0.1f })
        val score = classifier.classify(shortChunk)   // must not throw
        assertTrue(score in 0.0f..1.0f)
    }

    /**
     * Sample-rate invariance regression: the same band-limited signal sampled at 16 kHz and
     * 24 kHz must score nearly identically. A naive [HIGH_BAND_HZ, Nyquist] band would give the
     * higher-rate file a wider, higher high-band and a different score — the FoR inversion bug.
     */
    @Test
    fun `band-limited content scores the same regardless of sample rate`() {
        // Identical spectral content (tones ≤ 2.5 kHz, nothing in 6-8 kHz) at two sample rates.
        fun bandLimited(rate: Int) = AudioChunk(
            FloatArray(rate / 2) { i ->
                val t = i.toDouble() / rate
                (sin(2 * PI * 500 * t) * 0.35 + sin(2 * PI * 1200 * t) * 0.35 + sin(2 * PI * 2500 * t) * 0.30).toFloat()
            },
            rate
        )

        val score16 = classifier.classify(bandLimited(16_000))
        val score24 = classifier.classify(bandLimited(24_000))

        assertTrue(
            kotlin.math.abs(score16 - score24) < 0.10f,
            "Score must be sample-rate invariant: 16kHz=$score16 vs 24kHz=$score24"
        )
    }
}
