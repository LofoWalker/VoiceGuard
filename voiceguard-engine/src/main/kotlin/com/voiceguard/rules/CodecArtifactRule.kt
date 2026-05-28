package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.dsp.Fft
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlin.math.exp
import kotlin.math.ln

/**
 * VG-023 — Detects artifacts caused by TTS + VoIP + codec pipelines.
 *
 * Audio transmitted over narrowband voice codecs (GSM, G.711, AMR-NB) is hard-limited to
 * ~3.4 kHz. Wideband codecs (AMR-WB, Opus) extend this to ~7 kHz. Beyond bandwidth limiting,
 * codecs introduce quantization artifacts visible as spectral smoothing in the mid-band: instead
 * of the fine-grained spectral detail of natural speech, codec-processed audio has a "smeared"
 * mid-band and an abrupt high-frequency cutoff.
 *
 * Two signals:
 * 1. **High-frequency energy ratio**: very low energy above [NARROWBAND_CUTOFF_HZ] relative to
 *    mid-band indicates a bandwidth-limited source (codec or low-quality TTS).
 * 2. **Mid-band spectral flatness** (geometric mean / arithmetic mean): codec quantization
 *    smooths spectral peaks, increasing flatness. Pure TTS without codec compression may also
 *    exhibit higher flatness than natural speech due to its clean spectral envelope.
 *
 * Calibrated on phone-call datasets; on clean studio recordings (FoR) the HF ratio signal
 * dominates — both scores contribute, so the sweep will set the weight accordingly.
 */
class CodecArtifactRule(
    override val weight: Float = 0.50f
) : AudioDetectionRule {

    override val name = "CodecArtifactRule"
    override val isHeavyAnalysis = true

    private var chunkCount = 0
    private val hfRatios = ArrayList<Float>()
    private val flatnesses = ArrayList<Float>()

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val fftSize = minOf(Fft.nextPowerOfTwo(chunk.pcmData.size), DEFAULT_FFT_SIZE)
        val padded = chunk.pcmData.copyOf(fftSize)
        val mag = Fft.magnitudeSpectrum(padded)

        val nyquist = chunk.sampleRate / 2f
        val binHz = nyquist / mag.size

        val cutoffBin = (NARROWBAND_CUTOFF_HZ / binHz).toInt().coerceIn(1, mag.size - 1)
        val midLowBin = (MID_BAND_LOW_HZ / binHz).toInt().coerceIn(1, cutoffBin)

        val midEnergy = mag.slice(midLowBin until cutoffBin).sumOf { it.toDouble() }
        val hfEnergy = mag.slice(cutoffBin until mag.size).sumOf { it.toDouble() }
        val totalEnergy = midEnergy + hfEnergy
        hfRatios.add(if (totalEnergy > 1e-10) (hfEnergy / totalEnergy).toFloat() else 0f)

        val midSlice = mag.slice(midLowBin until cutoffBin)
        val flatness = if (midSlice.size >= 2) {
            val logSum = midSlice.sumOf { ln(it.toDouble() + EPS) }
            val geoMean = exp(logSum / midSlice.size)
            val arithMean = midSlice.average()
            if (arithMean > 1e-10) (geoMean / arithMean).toFloat().coerceIn(0f, 1f) else 0f
        } else 0f
        flatnesses.add(flatness)

        chunkCount++
        if (chunkCount < MIN_CHUNKS) return RuleResult(0f, 0f)

        val confidence = (chunkCount.toFloat() / RAMP_CHUNKS).coerceAtMost(1f)
        val meanHfRatio = hfRatios.average().toFloat()
        val meanFlatness = flatnesses.average().toFloat()

        // Low HF energy → bandwidth-limited → suspicious
        val hfScore = (1f - (meanHfRatio / HF_RATIO_REF).coerceAtMost(1f)).coerceIn(0f, 1f)
        // High flatness → codec-smoothed mid-band → suspicious
        val flatnessScore = (meanFlatness / FLATNESS_REF).coerceAtMost(1f).coerceIn(0f, 1f)

        return RuleResult((hfScore + flatnessScore) / 2f, confidence)
    }

    companion object {
        private const val DEFAULT_FFT_SIZE = 2048
        private const val MIN_CHUNKS = 3
        private const val RAMP_CHUNKS = 6
        private const val NARROWBAND_CUTOFF_HZ = 3400f
        private const val MID_BAND_LOW_HZ = 300f
        private const val EPS = 1e-10

        // ≥25% of energy in HF band is considered wideband (not codec-limited).
        private const val HF_RATIO_REF = 0.25f
        // Spectral flatness ≥ 0.5 is considered codec-smoothed.
        private const val FLATNESS_REF = 0.5f
    }
}
