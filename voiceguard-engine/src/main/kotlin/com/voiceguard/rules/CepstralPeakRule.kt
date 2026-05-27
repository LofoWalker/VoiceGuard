package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.dsp.Fft
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * R-05 — Cepstral Peak Prominence (CPP): how cleanly periodic the harmonic structure is.
 *
 * CPP is the height of the cepstral peak (at the pitch quefrency) above the cepstrum's regression
 * baseline. A strong, prominent peak means a very regular harmonic comb. Synthetic speech tends to
 * have an unusually clean/strong harmonic structure, so **high** CPP raises suspicion here.
 *
 * The quefrency search band is derived from the input sample rate, so the measure is sample-rate
 * invariant. The rule abstains (confidence 0) on silent chunks.
 *
 * Direction note: the suspicion mapping (high CPP ⇒ suspicious) is a provisional hypothesis. The
 * per-rule discrimination table on a labelled dataset confirms or flips it; flipping is a one-line
 * change ([SUSPICION_FROM_HIGH_CPP]).
 *
 * @param fftSize Power-of-two analysis window. Defaults to [DEFAULT_FFT_SIZE].
 */
class CepstralPeakRule(
    private val fftSize: Int = DEFAULT_FFT_SIZE,
    override val weight: Float = 0.15f
) : AudioDetectionRule {

    init {
        require(Fft.isPowerOfTwo(fftSize)) { "fftSize must be a power of two, got $fftSize" }
    }

    override val name = "CepstralPeakRule"
    override val isHeavyAnalysis = true

    private var chunkCount = 0

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val cpp = computeCpp(chunk.pcmData, chunk.sampleRate)
            ?: return RuleResult(suspicionScore = 0.0f, confidence = 0.0f)  // silence / not analysable

        chunkCount++
        val confidence = (chunkCount.toFloat() / RAMP_CHUNKS).coerceAtMost(1.0f)

        val normalized = (cpp / CPP_REF).coerceIn(0.0f, 1.0f)
        val suspicion = if (SUSPICION_FROM_HIGH_CPP) normalized else 1.0f - normalized

        return RuleResult(suspicion, confidence)
    }

    /**
     * Returns the cepstral peak prominence, or null when the chunk has no usable signal.
     *
     * Pipeline: Hann window → FFT → log-magnitude → IFFT (real cepstrum) → peak in the pitch
     * quefrency band minus the least-squares baseline of the cepstrum over that band.
     */
    private fun computeCpp(samples: FloatArray, sampleRate: Int): Float? {
        val windowed = applyHannWindow(samples, fftSize)
        if (windowed.all { it == 0.0f }) return null

        val re = windowed.copyOf()
        val im = FloatArray(fftSize)
        Fft.transform(re, im, inverse = false)

        val logMag = FloatArray(fftSize) { k ->
            ln(sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()) + EPS).toFloat()
        }

        // Real cepstrum = real part of IFFT(log-magnitude). log-magnitude is real & symmetric.
        val cRe = logMag.copyOf()
        val cIm = FloatArray(fftSize)
        Fft.transform(cRe, cIm, inverse = true)

        val minQ = (sampleRate / MAX_F0_HZ).toInt().coerceAtLeast(2)
        val maxQ = (sampleRate / MIN_F0_HZ).toInt().coerceAtMost(fftSize / 2 - 1)
        if (maxQ <= minQ) return null

        var peakQ = minQ
        var peakVal = cRe[minQ]
        for (q in minQ..maxQ) {
            if (cRe[q] > peakVal) { peakVal = cRe[q]; peakQ = q }
        }

        // Least-squares line c[q] ≈ a + b·q over the quefrency band → baseline at the peak.
        val count = maxQ - minQ + 1
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (q in minQ..maxQ) {
            val x = q.toDouble(); val y = cRe[q].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = count * sxx - sx * sx
        val slope = if (abs(denom) > 1e-12) (count * sxy - sx * sy) / denom else 0.0
        val intercept = (sy - slope * sx) / count
        val baselineAtPeak = (intercept + slope * peakQ).toFloat()

        return peakVal - baselineAtPeak
    }

    private fun applyHannWindow(input: FloatArray, size: Int): FloatArray {
        val windowed = FloatArray(size)
        for (i in 0 until minOf(input.size, size)) {
            val w = 0.5f * (1.0f - cos(2.0 * PI * i / (size - 1))).toFloat()
            windowed[i] = input[i] * w
        }
        return windowed
    }

    companion object {
        private const val DEFAULT_FFT_SIZE = 4096
        private const val RAMP_CHUNKS = 6

        // Fundamental-frequency band that bounds the cepstral quefrency search.
        private const val MIN_F0_HZ = 70f
        private const val MAX_F0_HZ = 400f

        private const val EPS = 1e-10

        // Provisional scale and direction — tune from the per-rule discrimination table.
        private const val CPP_REF = 1.5f
        private const val SUSPICION_FROM_HIGH_CPP = true
    }
}
