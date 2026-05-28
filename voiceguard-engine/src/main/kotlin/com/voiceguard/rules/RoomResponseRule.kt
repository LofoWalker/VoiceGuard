package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.dsp.Fft
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlin.math.abs

/**
 * VG-022 — Detects absence of realistic room acoustics or artificial reverberation signatures.
 *
 * Real microphone recordings carry the acoustic fingerprint of the recording environment: energy
 * from each phoneme smears into subsequent frames via early reflections and late reverberation,
 * and room resonances modulate the low-frequency spectrum. TTS audio rendered dry (anechoic) or
 * played through headphones lacks these signatures.
 *
 * Two complementary measures per chunk:
 * 1. **Tail-to-body energy ratio**: fraction of chunk energy in the last [TAIL_FRACTION] of
 *    samples vs the first [1 - TAIL_FRACTION]. Real rooms leave energy in the signal's tail
 *    (late reverberation); dry TTS decays sharply at word boundaries.
 * 2. **Low-frequency spectral roughness**: room modes create an irregular low-band spectrum.
 *    Dry TTS has an unusually smooth low-frequency profile (low roughness → suspicious).
 *
 * Low tail ratio AND low LF roughness together signal an anechoic environment.
 */
class RoomResponseRule(
    override val weight: Float = 0.50f
) : AudioDetectionRule {

    override val name = "RoomResponseRule"
    override val isHeavyAnalysis = true

    private var chunkCount = 0
    private val tailRatios = ArrayList<Float>()
    private val lfRoughnesses = ArrayList<Float>()

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val n = chunk.pcmData.size
        if (n < MIN_SAMPLES) return RuleResult(0f, 0f)

        chunkCount++

        val bodyEnd = (n * (1f - TAIL_FRACTION)).toInt()
        val bodyEnergy = sumSq(chunk.pcmData, 0, bodyEnd)
        val tailEnergy = sumSq(chunk.pcmData, bodyEnd, n)
        val totalEnergy = bodyEnergy + tailEnergy
        tailRatios.add(if (totalEnergy > 1e-10f) tailEnergy / totalEnergy else 0f)

        val fftSize = minOf(Fft.nextPowerOfTwo(n), DEFAULT_FFT_SIZE)
        val padded = chunk.pcmData.copyOf(fftSize)
        val mag = Fft.magnitudeSpectrum(padded)
        val nyquist = chunk.sampleRate / 2f
        val lfBins = (mag.size * LF_CUTOFF_HZ / nyquist).toInt().coerceIn(4, mag.size / 4)
        val lfRoughness = if (lfBins >= 2) {
            (1 until lfBins).sumOf { abs(mag[it] - mag[it - 1]).toDouble() }.toFloat() / (lfBins - 1)
        } else 0f
        lfRoughnesses.add(lfRoughness)

        if (chunkCount < MIN_CHUNKS) return RuleResult(0f, 0f)

        val confidence = (chunkCount.toFloat() / RAMP_CHUNKS).coerceAtMost(1f)
        val meanTailRatio = tailRatios.average().toFloat()
        val meanLfRoughness = lfRoughnesses.average().toFloat()

        val tailScore = (1f - (meanTailRatio / TAIL_RATIO_REF).coerceAtMost(1f)).coerceIn(0f, 1f)
        val lfScore = (1f - (meanLfRoughness / LF_ROUGHNESS_REF).coerceAtMost(1f)).coerceIn(0f, 1f)

        return RuleResult((tailScore + lfScore) / 2f, confidence)
    }

    private fun sumSq(samples: FloatArray, from: Int, until: Int): Float {
        var s = 0.0
        for (i in from until until) s += samples[i].toDouble() * samples[i].toDouble()
        return s.toFloat()
    }

    companion object {
        private const val MIN_SAMPLES = 64
        private const val MIN_CHUNKS = 3
        private const val RAMP_CHUNKS = 6
        private const val DEFAULT_FFT_SIZE = 2048
        private const val TAIL_FRACTION = 0.20f    // last 20% of the chunk = reverb tail region
        private const val LF_CUTOFF_HZ = 500f      // room modes are most visible below 500 Hz
        private const val TAIL_RATIO_REF = 0.15f   // ≥15% tail energy → organic reverberation
        private const val LF_ROUGHNESS_REF = 0.01f // LF spectral roughness reference
    }
}
