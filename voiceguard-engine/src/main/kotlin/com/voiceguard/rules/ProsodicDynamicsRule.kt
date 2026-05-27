package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.dsp.PitchAnalysis
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import com.voiceguard.domain.service.computeRms
import kotlin.math.sqrt

/**
 * R-06 — Prosodic dynamics: how much the pitch and loudness vary over the whole utterance.
 *
 * Natural human prosody is lively — F0 and energy fluctuate across syllables and phrases. Many
 * TTS systems are comparatively flat or over-smoothed. So **low** variability raises suspicion.
 *
 * This rule is stateful per stream: it accumulates the per-chunk F0 (voiced chunks only) and RMS
 * energy, then scores the combined coefficient of variation. It needs a fresh instance per file —
 * guaranteed by building rules inside the processor factory.
 *
 * Not [isHeavyAnalysis]: it must run on every chunk so the variability estimate samples the whole
 * utterance rather than only its dynamic segments.
 */
class ProsodicDynamicsRule(
    private val invertDirection: Boolean = false
) : AudioDetectionRule {

    override val name = "ProsodicDynamicsRule"
    override val weight = 0.15f

    private val f0Contour = ArrayList<Float>()
    private val rmsEnvelope = ArrayList<Float>()
    private var chunksSeen = 0

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        chunksSeen++
        rmsEnvelope.add(computeRms(chunk.pcmData))

        val pitch = PitchAnalysis.estimatePitch(chunk.pcmData, chunk.sampleRate)
        if (pitch.isVoiced && pitch.voicingStrength >= VOICING_THRESHOLD) {
            f0Contour.add(pitch.f0Hz)
        }

        if (chunksSeen < MIN_CHUNKS) {
            return RuleResult(suspicionScore = 0.0f, confidence = 0.0f)
        }

        val covs = buildList {
            if (f0Contour.size >= 2) add(coefficientOfVariation(f0Contour))
            if (rmsEnvelope.size >= 2) add(coefficientOfVariation(rmsEnvelope))
        }
        if (covs.isEmpty()) return RuleResult(0.0f, 0.0f)

        val variability = covs.average().toFloat()
        // Default premise: low variability (flat prosody) → high suspicion; lively human → 0.
        // [invertDirection] reverses it for corpora where the synthetic voices are *more* dynamic
        // than the (flat, read) human samples — calibrated on the FoR validation split.
        val normalized = (variability / VARIABILITY_REF).coerceIn(0.0f, 1.0f)
        val suspicion = if (invertDirection) normalized else 1.0f - normalized
        val confidence = (chunksSeen.toFloat() / RAMP_CHUNKS).coerceAtMost(1.0f)

        return RuleResult(suspicion, confidence)
    }

    private fun coefficientOfVariation(values: List<Float>): Float {
        val mean = values.average()
        if (mean <= 1e-9) return 0f
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return (sqrt(variance) / mean).toFloat()
    }

    companion object {
        private const val MIN_CHUNKS = 3
        private const val RAMP_CHUNKS = 6
        private const val VOICING_THRESHOLD = 0.5f

        // Combined F0+energy coefficient of variation at or above which prosody reads as organic.
        // Provisional — calibrate from the per-rule discrimination table.
        private const val VARIABILITY_REF = 0.30f
    }
}
