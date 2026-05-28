package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.dsp.PitchAnalysis
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlin.math.sqrt

/**
 * VG-024 — Detects absence of natural human speech imperfections.
 *
 * Human speakers produce characteristic imperfections that TTS systems rarely replicate:
 * micro-tremors (low-frequency amplitude modulation at 4–8 Hz), erratic pitch trajectories
 * with direction reversals, and irregular amplitude micro-dynamics within voiced segments.
 * High-quality TTS voices are unnaturally "clean": smoother, more controlled, and more
 * consistent than any real speaker.
 *
 * Two markers whose ABSENCE raises suspicion:
 * 1. **Micro-tremor** — variance of sub-frame RMS within a voiced chunk. Low variance means
 *    the amplitude profile is too uniform (no physiological tremor).
 * 2. **Pitch instability** — frequency of direction changes in the F0 trajectory. Real speakers
 *    have many small pitch reversals; TTS interpolates smoothly between pitch targets.
 *
 * Low combined imperfection score → high suspicion.
 */
class HumanImperfectionRule(
    override val weight: Float = 0.50f
) : AudioDetectionRule {

    override val name = "HumanImperfectionRule"
    override val isHeavyAnalysis = true

    private var voicedChunks = 0
    private val f0History = ArrayList<Float>()
    private val tremorVariances = ArrayList<Float>()

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val pitch = PitchAnalysis.estimatePitch(chunk.pcmData, chunk.sampleRate)
        if (!pitch.isVoiced || pitch.voicingStrength < VOICING_THRESHOLD) return RuleResult(0f, 0f)

        voicedChunks++
        f0History.add(pitch.f0Hz)
        tremorVariances.add(subFrameRmsVariance(chunk.pcmData))

        if (voicedChunks < MIN_VOICED_CHUNKS) return RuleResult(0f, 0f)

        val confidence = (voicedChunks.toFloat() / RAMP_CHUNKS).coerceAtMost(1f)

        val meanTremor = tremorVariances.average().toFloat()
        val pitchInstability = pitchDirectionChangeRate(f0History)

        val tremorScore = (meanTremor / TREMOR_REF).coerceAtMost(1f).coerceIn(0f, 1f)
        val pitchScore = (pitchInstability / PITCH_INSTABILITY_REF).coerceAtMost(1f).coerceIn(0f, 1f)

        val imperfection = (tremorScore + pitchScore) / 2f
        return RuleResult(1f - imperfection, confidence)
    }

    /**
     * Variance of per-sub-frame RMS within [samples] — captures micro-tremor amplitude modulation.
     */
    private fun subFrameRmsVariance(samples: FloatArray): Float {
        val frameSize = samples.size / SUB_FRAMES
        if (frameSize < 2) return 0f
        val rmsValues = FloatArray(SUB_FRAMES) { i ->
            val from = i * frameSize
            val until = minOf(from + frameSize, samples.size)
            var sum = 0.0
            for (j in from until until) sum += samples[j].toDouble() * samples[j].toDouble()
            sqrt(sum / (until - from)).toFloat()
        }
        val mean = rmsValues.average().toFloat()
        if (mean <= 1e-9f) return 0f
        return rmsValues.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / SUB_FRAMES
    }

    /**
     * Fraction of consecutive F0 pairs that reverse direction — captures pitch micro-instability.
     */
    private fun pitchDirectionChangeRate(f0s: List<Float>): Float {
        if (f0s.size < 3) return 0f
        var changes = 0
        for (i in 1 until f0s.size - 1) {
            val d1 = f0s[i] - f0s[i - 1]
            val d2 = f0s[i + 1] - f0s[i]
            if (d1 * d2 < 0f) changes++
        }
        return changes.toFloat() / (f0s.size - 2)
    }

    companion object {
        private const val MIN_VOICED_CHUNKS = 3
        private const val RAMP_CHUNKS = 8
        private const val VOICING_THRESHOLD = 0.4f
        private const val SUB_FRAMES = 8

        // Organic imperfection references — below these values the voice is suspiciously clean.
        private const val TREMOR_REF = 1e-4f            // RMS variance within voiced chunks
        private const val PITCH_INSTABILITY_REF = 0.5f  // 50% direction-change rate
    }
}
