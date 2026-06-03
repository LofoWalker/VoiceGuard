package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlin.math.sqrt

/**
 * VG-017 — Detects unnatural pause distributions and overly regular conversational pacing.
 *
 * TTS systems produce pauses at syntactically predictable positions with suspiciously uniform
 * duration. Human speech has irregular micro-silences, hesitation gaps, and variable inter-word
 * pauses driven by breathing, coarticulation, and thought. This rule extracts silence intervals
 * from each 500 ms chunk at the sub-frame level and tracks their distribution over time.
 *
 * Two signals contribute:
 * 1. **Density variance** — how much the fraction of silent frames varies chunk-to-chunk.
 *    TTS is suspiciously stable; human speech fluctuates widely.
 * 2. **Pause duration variance** — how much individual pause run-lengths vary.
 *    TTS pauses cluster tightly; human hesitations are irregular.
 *
 * Low variance on both → high suspicion (default direction).
 *
 * [invertDirection] reverses the score for corpora (e.g. FoR read-speech) where TTS produces
 * *more* varied pauses than the (flat, read) human samples — calibrated on the FoR testing split
 * (AUC=0.41, d=−0.52 without inversion → AUC≈0.59 with inversion).
 *
 * Not [isHeavyAnalysis]: must run on every chunk to build an accurate pause distribution.
 */
class MicroPauseDistributionRule(
    override val weight: Float = 0.50f,
    private val silenceThreshold: Float = 0.015f,
    private val framesPerChunk: Int = 32,
    private val invertDirection: Boolean = false
) : AudioDetectionRule {

    override val name = "MicroPauseDistributionRule"

    private var chunksAnalyzed = 0
    private val silenceDensities = ArrayList<Float>()
    private val pauseDurations = ArrayList<Float>()

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        chunksAnalyzed++
        val frameSize = chunk.pcmData.size / framesPerChunk
        if (frameSize < 2) return RuleResult(0f, 0f)

        val silentFrames = BooleanArray(framesPerChunk) { f ->
            val start = f * frameSize
            val end = minOf(start + frameSize, chunk.pcmData.size)
            frameRms(chunk.pcmData, start, end) < silenceThreshold
        }

        silenceDensities.add(silentFrames.count { it }.toFloat() / framesPerChunk)

        var run = 0
        for (silent in silentFrames) {
            if (silent) {
                run++
            } else if (run > 0) {
                pauseDurations.add(run.toFloat())
                run = 0
            }
        }
        if (run > 0) pauseDurations.add(run.toFloat())

        if (chunksAnalyzed < MIN_CHUNKS) return RuleResult(0f, 0f)

        val confidence = (chunksAnalyzed.toFloat() / RAMP_CHUNKS).coerceAtMost(1f)

        val densityVariance = sampleVariance(silenceDensities)
        val pauseVariance = if (pauseDurations.size >= 2) sampleVariance(pauseDurations) else FALLBACK_VARIANCE

        val densityRaw = (densityVariance / DENSITY_VARIANCE_REF).coerceAtMost(1f).coerceIn(0f, 1f)
        val pauseRaw   = (pauseVariance   / PAUSE_VARIANCE_REF).coerceAtMost(1f).coerceIn(0f, 1f)

        // Default: low variance (uniform) → suspicious. Inverted: high variance → suspicious.
        val densityScore = if (invertDirection) densityRaw else 1f - densityRaw
        val pauseScore   = if (invertDirection) pauseRaw   else 1f - pauseRaw

        return RuleResult((densityScore + pauseScore) / 2f, confidence)
    }

    private fun frameRms(samples: FloatArray, from: Int, until: Int): Float {
        if (until <= from) return 0f
        var sum = 0.0
        for (i in from until until) sum += samples[i].toDouble() * samples[i].toDouble()
        return sqrt(sum / (until - from)).toFloat()
    }

    private fun sampleVariance(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        return (values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)).toFloat()
    }

    companion object {
        private const val MIN_CHUNKS = 3
        private const val RAMP_CHUNKS = 8

        // Organic human variance references — below these thresholds the signal is suspiciously regular.
        private const val DENSITY_VARIANCE_REF = 0.02f   // fraction² of silent frames
        private const val PAUSE_VARIANCE_REF = 2.0f      // sub-frame run-length units²
        private const val FALLBACK_VARIANCE = PAUSE_VARIANCE_REF  // used when no pauses detected yet
    }
}
