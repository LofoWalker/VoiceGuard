package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.dsp.Fft
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlin.math.ln

/**
 * VG-025 — Measures entropy differences between human and synthetic speech.
 *
 * TTS-generated speech has lower spectral and temporal entropy than natural human speech because:
 * - The synthesis model produces a predictable spectral shape concentrated in harmonic peaks
 *   (low spectral entropy — energy is "ordered").
 * - The temporal sample distribution is tighter than for natural speech (low temporal entropy).
 *
 * Two entropy measures, combined:
 * 1. **Spectral entropy**: −Σ p(k) ln p(k) over the normalised magnitude spectrum. Lower for
 *    clean TTS (energy concentrated in harmonic peaks vs. more distributed for natural noise).
 * 2. **Temporal entropy**: derived from the sample amplitude histogram within the chunk. Lower
 *    for TTS (tighter amplitude distribution due to smooth synthesis).
 *
 * Both are normalised by their theoretical maximum (uniform distribution) so the score is
 * invariant to chunk length and sample rate.
 *
 * Low combined entropy → high suspicion.
 */
class SpeechEntropyRule(
    override val weight: Float = 0.50f,
    private val amplitudeBins: Int = 32
) : AudioDetectionRule {

    override val name = "SpeechEntropyRule"
    override val isHeavyAnalysis = true

    private val spectralEntropies = ArrayList<Float>()
    private val temporalEntropies = ArrayList<Float>()

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        if (chunk.pcmData.isEmpty()) return RuleResult(0f, 0f)

        val fftSize = minOf(Fft.nextPowerOfTwo(chunk.pcmData.size), DEFAULT_FFT_SIZE)
        val padded = chunk.pcmData.copyOf(fftSize)
        val mag = Fft.magnitudeSpectrum(padded)

        spectralEntropies.add(shannonEntropy(mag))
        temporalEntropies.add(histogramEntropy(chunk.pcmData))

        val n = spectralEntropies.size
        if (n < MIN_CHUNKS) return RuleResult(0f, 0f)

        val confidence = (n.toFloat() / RAMP_CHUNKS).coerceAtMost(1f)

        val maxSpectralEntropy = ln(fftSize / 2.0).toFloat()
        val maxTemporalEntropy = ln(amplitudeBins.toDouble()).toFloat()

        val meanSpectral = spectralEntropies.average().toFloat()
        val meanTemporal = temporalEntropies.average().toFloat()

        val spectralScore = if (maxSpectralEntropy > 0f) (meanSpectral / maxSpectralEntropy).coerceIn(0f, 1f) else 0f
        val temporalScore = if (maxTemporalEntropy > 0f) (meanTemporal / maxTemporalEntropy).coerceIn(0f, 1f) else 0f

        val suspicion = (1f - (spectralScore + temporalScore) / 2f).coerceIn(0f, 1f)
        return RuleResult(suspicion, confidence)
    }

    /** Shannon entropy of [values] treated as an unnormalized probability distribution. */
    private fun shannonEntropy(values: FloatArray): Float {
        val total = values.sumOf { it.toDouble() }
        if (total <= 0.0) return 0f
        var entropy = 0.0
        for (v in values) {
            if (v > 0f) {
                val p = v / total
                entropy -= p * ln(p)
            }
        }
        return entropy.toFloat()
    }

    /** Entropy of [samples] bucketed into [amplitudeBins] amplitude bins. */
    private fun histogramEntropy(samples: FloatArray): Float {
        val min = samples.minOrNull() ?: return 0f
        val max = samples.maxOrNull() ?: return 0f
        if (max - min < 1e-10f) return 0f
        val histogram = IntArray(amplitudeBins)
        for (s in samples) {
            val bin = ((s - min) / (max - min) * (amplitudeBins - 1)).toInt().coerceIn(0, amplitudeBins - 1)
            histogram[bin]++
        }
        val total = samples.size.toDouble()
        var entropy = 0.0
        for (count in histogram) {
            if (count > 0) {
                val p = count / total
                entropy -= p * ln(p)
            }
        }
        return entropy.toFloat()
    }

    companion object {
        private const val DEFAULT_FFT_SIZE = 2048
        private const val MIN_CHUNKS = 3
        private const val RAMP_CHUNKS = 6
    }
}
