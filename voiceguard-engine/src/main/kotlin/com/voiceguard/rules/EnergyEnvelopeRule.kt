package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * VG-021 — Detects overly controlled or artificial speech energy envelopes.
 *
 * Real microphone recordings have organic amplitude dynamics: the energy envelope has irregular
 * attack/release curves and abrupt transitions driven by consonants, breaths, and coarticulation.
 * TTS systems apply smooth spectral envelopes and dynamic range compression, producing
 * unnaturally low frame-to-frame energy variation.
 *
 * Two signals:
 * 1. **Envelope roughness** — mean absolute first difference of the per-frame RMS envelope
 *    within each chunk. Very smooth (low roughness) → suspicious.
 * 2. **Roughness variance** — how much the roughness varies chunk-to-chunk.
 *    TTS maintains suspiciously consistent roughness; human speech fluctuates.
 *
 * Not [isHeavyAnalysis]: must run on every chunk to build the roughness trajectory.
 */
class EnergyEnvelopeRule(
    override val weight: Float = 0.50f,
    private val numFrames: Int = 16
) : AudioDetectionRule {

    override val name = "EnergyEnvelopeRule"

    private val chunkRoughnesses = ArrayList<Float>()

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val frameSize = chunk.pcmData.size / numFrames
        if (frameSize < 2) return RuleResult(0f, 0f)

        val envelope = FloatArray(numFrames) { f ->
            val start = f * frameSize
            val end = minOf(start + frameSize, chunk.pcmData.size)
            frameRms(chunk.pcmData, start, end)
        }

        val roughness = (1 until envelope.size).map { abs(envelope[it] - envelope[it - 1]) }.average().toFloat()
        chunkRoughnesses.add(roughness)

        if (chunkRoughnesses.size < MIN_CHUNKS) return RuleResult(0f, 0f)

        val confidence = (chunkRoughnesses.size.toFloat() / RAMP_CHUNKS).coerceAtMost(1f)

        val meanRoughness = chunkRoughnesses.average().toFloat()
        val roughnessVariance = sampleVariance(chunkRoughnesses)

        val smoothnessScore = (1f - (meanRoughness / ROUGHNESS_REF).coerceAtMost(1f)).coerceIn(0f, 1f)
        val consistencyScore = (1f - (roughnessVariance / ROUGHNESS_VARIANCE_REF).coerceAtMost(1f)).coerceIn(0f, 1f)

        return RuleResult((smoothnessScore + consistencyScore) / 2f, confidence)
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
        private const val RAMP_CHUNKS = 6

        // Mean roughness reference: below this, the envelope is suspiciously smooth.
        private const val ROUGHNESS_REF = 0.005f
        // Roughness variance reference: below this, the roughness itself is suspiciously stable.
        private const val ROUGHNESS_VARIANCE_REF = 1e-6f
    }
}
