package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.dsp.PitchAnalysis
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import com.voiceguard.domain.service.computeRms

/**
 * VG-018 — Detects lack of natural emotional fluctuations in synthetic speech.
 *
 * Human emotional expression is characterized by irregular variability in pitch and loudness
 * across the utterance: some phrases are energetically delivered, others nearly monotone. TTS
 * systems maintain suspiciously consistent local variability throughout — neither too flat nor
 * too animated, but always "just right" in a way that betrays generation.
 *
 * This rule measures *meta-variance* — the variance of local variances computed over a rolling
 * window — to distinguish organically fluctuating expression from uniformly consistent TTS.
 *
 * - Low meta-variance (local variability is always the same) → high suspicion.
 *
 * Complementary to [ProsodicDynamicsRule] (which measures global CoV across the whole utterance):
 * this rule detects whether the expressiveness itself is consistent, not just whether it is flat.
 *
 * Not [isHeavyAnalysis]: must run on every chunk to track the expressiveness trajectory.
 */
class EmotionalVarianceRule(
    override val weight: Float = 0.50f,
    private val windowSize: Int = 4
) : AudioDetectionRule {

    override val name = "EmotionalVarianceRule"

    private val rmsHistory = ArrayList<Float>()
    private val pitchHistory = ArrayList<Float>()
    private val localRmsVariances = ArrayList<Float>()
    private val localPitchVariances = ArrayList<Float>()

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        rmsHistory.add(computeRms(chunk.pcmData))

        val pitch = PitchAnalysis.estimatePitch(chunk.pcmData, chunk.sampleRate)
        if (pitch.isVoiced && pitch.voicingStrength >= VOICING_THRESHOLD) {
            pitchHistory.add(pitch.f0Hz)
        }

        if (rmsHistory.size >= windowSize) {
            localRmsVariances.add(sampleVariance(rmsHistory.takeLast(windowSize)))
        }
        if (pitchHistory.size >= windowSize) {
            localPitchVariances.add(sampleVariance(pitchHistory.takeLast(windowSize)))
        }

        if (rmsHistory.size < MIN_CHUNKS || localRmsVariances.size < 2) return RuleResult(0f, 0f)

        val confidence = (rmsHistory.size.toFloat() / RAMP_CHUNKS).coerceAtMost(1f)

        val rmsMetaVar = sampleVariance(localRmsVariances)
        val rmsScore = (1f - (rmsMetaVar / RMS_META_VAR_REF).coerceAtMost(1f)).coerceIn(0f, 1f)

        val suspicion = if (localPitchVariances.size >= 2) {
            val pitchMetaVar = sampleVariance(localPitchVariances)
            val pitchScore = (1f - (pitchMetaVar / PITCH_META_VAR_REF).coerceAtMost(1f)).coerceIn(0f, 1f)
            (rmsScore + pitchScore) / 2f
        } else {
            rmsScore
        }

        return RuleResult(suspicion, confidence)
    }

    private fun sampleVariance(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        return (values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)).toFloat()
    }

    companion object {
        private const val MIN_CHUNKS = 5
        private const val RAMP_CHUNKS = 10
        private const val VOICING_THRESHOLD = 0.5f

        // Organic references: below these values the local variability is suspiciously consistent.
        private const val RMS_META_VAR_REF = 1e-5f   // variance of local RMS variances
        private const val PITCH_META_VAR_REF = 25f   // variance of local F0 variances (Hz²)²
    }
}
