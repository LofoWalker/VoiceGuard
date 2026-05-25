package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import com.voiceguard.domain.service.computeRms
import kotlin.math.sqrt

/**
 * R-03 — Detects suspiciously linear background noise patterns.
 *
 * Two signals indicate AI-generated audio:
 * 1. **Digital silence** — near-zero RMS across the chunk (real rooms always have ambient noise).
 * 2. **Loop patterns** — amplitude profile of successive chunks is nearly identical, suggesting
 *    a short ambient-noise segment is being looped by the synthesis engine.
 *
 * Confidence ramps linearly over [RAMP_CHUNKS] chunks (≈ 2 seconds at 500 ms/chunk), reaching
 * its target operating range quickly so it can contribute to early-exit decisions before any
 * speech-turn switch is observed.
 *
 * @param silenceRmsThreshold  RMS magnitude below which a chunk is classified as digital silence.
 * @param loopSimilarityThreshold  Cosine-similarity threshold above which successive amplitude
 *   profiles are considered repeating loops.
 */
class NoiseLinearityRule(
    private val silenceRmsThreshold: Float = 0.01f,
    private val loopSimilarityThreshold: Float = 0.95f
) : AudioDetectionRule {

    override val name = "NoiseLinearityRule"
    override val weight = 0.20f

    // This rule is the lightweight sentinel — it always runs, triggers early-exit evaluation,
    // and is never suppressed by intermittent sampling (ADR architecture §Detection Rules table).
    override val isAlwaysActive = true
    override val isEarlyExitTrigger = true

    private var chunkCount = 0
    private var previousAmplitudeProfile: FloatArray? = null

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        chunkCount++
        val confidence = (chunkCount.toFloat() / RAMP_CHUNKS).coerceAtMost(1.0f)

        val rms = computeRms(chunk.pcmData)
        val suspicion = when {
            rms < silenceRmsThreshold -> SILENCE_SUSPICION
            else -> computeLoopSuspicion(chunk.pcmData)
        }

        return RuleResult(suspicion, confidence)
    }

    private fun computeLoopSuspicion(samples: FloatArray): Float {
        val profile = buildAmplitudeProfile(samples, PROFILE_BINS)
        val previous = previousAmplitudeProfile
        previousAmplitudeProfile = profile

        if (previous == null) return ORGANIC_SUSPICION

        val similarity = cosineSimilarity(previous, profile)
        return if (similarity > loopSimilarityThreshold) LOOP_SUSPICION else ORGANIC_SUSPICION
    }

    /**
     * Extracts [bins] evenly-spaced sample values from [samples] as a chunk fingerprint.
     *
     * Using raw sample values (not per-bin RMS) is critical for loop detection: a perfect loop
     * repeats identical sample values at each position, giving fingerprint similarity = 1.0.
     * Organic audio produces different sample values each chunk regardless of spectral content,
     * yielding similarity near 0. Per-bin RMS is NOT used because periodic waveforms have
     * constant RMS across all windows, making them indistinguishable from loops via RMS alone.
     */
    private fun buildAmplitudeProfile(samples: FloatArray, bins: Int): FloatArray {
        val step = (samples.size.toDouble() / bins).coerceAtLeast(1.0)
        return FloatArray(bins) { i ->
            val idx = (i * step).toInt().coerceIn(0, samples.size - 1)
            samples[idx]
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val dot = a.indices.sumOf { (a[it] * b[it]).toDouble() }
        val magA = sqrt(a.sumOf { it.toDouble() * it.toDouble() })
        val magB = sqrt(b.sumOf { it.toDouble() * it.toDouble() })
        if (magA == 0.0 || magB == 0.0) return 0f
        return (dot / (magA * magB)).toFloat()
    }

    companion object {
        private const val RAMP_CHUNKS = 4
        private const val PROFILE_BINS = 8
        private const val SILENCE_SUSPICION = 0.9f
        private const val LOOP_SUSPICION = 0.8f
        private const val ORGANIC_SUSPICION = 0.05f
    }
}

