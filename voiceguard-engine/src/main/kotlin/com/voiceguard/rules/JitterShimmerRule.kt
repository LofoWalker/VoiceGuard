package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.dsp.PitchAnalysis
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule

/**
 * R-04 — Detects the unnaturally regular voicing of synthetic speech.
 *
 * A real human voice is biologically imperfect: the glottal period and amplitude vary slightly
 * from cycle to cycle (jitter and shimmer). TTS / vocoder speech is smoother — its periods and
 * amplitudes are far more regular. So **low** jitter+shimmer raises suspicion.
 *
 * Per chunk: estimate F0 by autocorrelation; if the chunk is unvoiced (silence, fricative, noise)
 * the rule abstains (confidence 0). Otherwise it measures local jitter/shimmer over the glottal
 * cycles and maps the combined irregularity to a suspicion score. All measures are relative
 * ratios, so the score is invariant to the input sample rate.
 *
 * Confidence ramps over voiced chunks only — a file that is never voiced never gains confidence.
 */
class JitterShimmerRule(
    override val weight: Float = 0.20f
) : AudioDetectionRule {

    override val name = "JitterShimmerRule"
    override val isHeavyAnalysis = true

    private var voicedChunks = 0

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val pitch = PitchAnalysis.estimatePitch(chunk.pcmData, chunk.sampleRate)
        if (!pitch.isVoiced || pitch.voicingStrength < VOICING_THRESHOLD) {
            return RuleResult(suspicionScore = 0.0f, confidence = 0.0f)
        }

        val pert = PitchAnalysis.perturbation(chunk.pcmData, pitch.periodSamples)
        if (pert.cycles < MIN_CYCLES) {
            return RuleResult(suspicionScore = 0.0f, confidence = 0.0f)
        }

        voicedChunks++
        val confidence = (voicedChunks.toFloat() / RAMP_CHUNKS).coerceAtMost(1.0f)

        val irregularity = JITTER_WEIGHT * pert.jitter + SHIMMER_WEIGHT * pert.shimmer
        // Low irregularity (too regular) → high suspicion; organic irregularity → 0.
        val suspicion = (1.0f - irregularity / IRREGULARITY_REF).coerceIn(0.0f, 1.0f)

        return RuleResult(suspicion, confidence)
    }

    companion object {
        private const val RAMP_CHUNKS = 6
        private const val VOICING_THRESHOLD = 0.5f
        private const val MIN_CYCLES = 4

        private const val JITTER_WEIGHT = 0.5f
        private const val SHIMMER_WEIGHT = 0.5f

        // Combined jitter+shimmer at or above which the voice is considered organically irregular.
        // Provisional — calibrate from the per-rule discrimination table on a labelled dataset.
        private const val IRREGULARITY_REF = 0.08f
    }
}
