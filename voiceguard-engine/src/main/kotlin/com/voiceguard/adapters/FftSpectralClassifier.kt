package com.voiceguard.adapters

import com.voiceguard.domain.dsp.Fft
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.port.SpectralClassifierPort
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/**
 * Pure-JVM DSP implementation of [SpectralClassifierPort].
 *
 * Detects spectral signatures typical of neural-vocoder-generated speech without any ML model:
 *
 * 1. **Band-limiting score** — some TTS vocoders apply a hard low-pass filter below the channel
 *    Nyquist. A sharply attenuated high band (relative to the mid band) raises suspicion.
 *    Analysis is capped at a fixed ceiling ([ANALYSIS_CEILING_HZ]) so the score is invariant to
 *    the input sample rate and never confuses a low Nyquist with a vocoder cutoff.
 *
 * 2. **Spectral flatness (Wiener entropy)** — geometric_mean(PSD) / arithmetic_mean(PSD).
 *    A near-flat (white-noise-like) spectrum scores higher; peaky formant-rich speech scores low.
 *    This is a secondary cue that complements band-limiting.
 *
 * The final score is a weighted blend of both cues, clamped to [0.0, 1.0].
 * 0.0 = confident human voice; 1.0 = confident synthetic/vocoder artifact.
 *
 * **Accuracy disclaimer:** These heuristics catch coarse vocoder artifacts (hard band-limiting,
 * spectral uniformity). They will not reliably detect high-quality modern TTS. They serve as
 * Phase-1-without-ML baseline until a trained model is integrated in Phase 2.
 *
 * @param fftSize Power-of-two window applied per chunk. Defaults to [DEFAULT_FFT_SIZE].
 */
class FftSpectralClassifier(
    private val fftSize: Int = DEFAULT_FFT_SIZE
) : SpectralClassifierPort {

    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) {
            "fftSize must be a positive power of two, got $fftSize"
        }
    }

    override fun classify(chunk: AudioChunk): Float {
        val windowed = applyHannWindow(chunk.pcmData, fftSize)
        val magnitude = Fft.magnitudeSpectrum(windowed)
        val nyquistBins = magnitude.size                    // size = fftSize/2
        val sampleRate = chunk.sampleRate

        val bandLimitScore = computeBandLimitScore(magnitude, sampleRate, nyquistBins)
        val flatnessScore = computeSpectralFlatnessScore(magnitude)

        return (WEIGHT_BAND_LIMIT * bandLimitScore + WEIGHT_FLATNESS * flatnessScore)
            .coerceIn(0.0f, 1.0f)
    }

    /**
     * Band-limiting score: measures the relative energy drop in the high-frequency band
     * compared to the mid-frequency band.
     *
     * A hard cutoff above [HIGH_BAND_HZ] (typical of TTS pipelines capped at 8 kHz) yields
     * near-zero high-band energy while mid-band energy is normal → high suspicion score.
     * Organic speech fills the high band because real vocal cords and room acoustics produce
     * fricatives and sibilants up to 8 kHz and beyond.
     */
    private fun computeBandLimitScore(
        magnitude: FloatArray,
        sampleRate: Int,
        nyquistBins: Int
    ): Float {
        val binHz = sampleRate.toFloat() / (2 * nyquistBins)

        val midLow = (MID_BAND_LOW_HZ / binHz).toInt().coerceIn(0, nyquistBins - 1)
        val midHigh = (MID_BAND_HIGH_HZ / binHz).toInt().coerceIn(0, nyquistBins - 1)
        val highLow = (HIGH_BAND_HZ / binHz).toInt().coerceIn(0, nyquistBins - 1)
        // Sample-rate invariance: cap the high band at a fixed ceiling present in every input,
        // never the file's own Nyquist. Otherwise the high band would span [HIGH_BAND_HZ, Nyquist]
        // and its width/upper-bound would track the sample rate — turning the score into a
        // sample-rate detector rather than a band-limiting detector. (On a mixed-rate dataset
        // where rate correlates with the label this produces a systematically inverted score.)
        val highHigh = (ANALYSIS_CEILING_HZ / binHz).toInt().coerceIn(0, nyquistBins - 1)

        if (midLow >= midHigh || highLow >= highHigh) return 0.0f

        val midEnergy = magnitude.slice(midLow..midHigh).sumOf { it.toDouble() * it }.toFloat()
        val highEnergy = magnitude.slice(highLow..highHigh).sumOf { it.toDouble() * it }.toFloat()

        if (midEnergy == 0.0f) return 0.0f

        // Ratio of high-to-mid energy: natural speech ~= 0.05–0.3; band-limited TTS ~= 0.001–0.02.
        val ratio = highEnergy / midEnergy
        return if (ratio >= ORGANIC_HIGH_MID_RATIO) 0.0f
        else (1.0f - ratio / ORGANIC_HIGH_MID_RATIO).coerceIn(0.0f, 1.0f)
    }

    /**
     * Spectral flatness score: measures how uniform the power spectrum is.
     *
     * Wiener entropy = geometric_mean(PSD) / arithmetic_mean(PSD), in [0.0, 1.0].
     * A very flat (white-noise-like) spectrum → entropy near 1.0 → likely digital silence padding
     * or an uncorrelated noise source typical of vocoder background fill.
     * Peaky (formant-rich) speech → entropy near 0.0 → organic.
     *
     * This cue is secondary; it complements band-limiting by catching unnatural smoothness
     * even when the frequency range is full.
     */
    private fun computeSpectralFlatnessScore(magnitude: FloatArray): Float {
        val psd = FloatArray(magnitude.size) { magnitude[it] * magnitude[it] }
        val meanPsd = psd.average().toFloat()
        if (meanPsd < 1e-10f) return 0.0f   // silence — handled by NoiseLinearityRule already

        val logSum = psd.sumOf { p -> if (p < 1e-30f) -69.0 else ln(p.toDouble()) }
        val geometricMean = Math.exp(logSum / psd.size).toFloat()
        val flatness = (geometricMean / meanPsd).coerceIn(0.0f, 1.0f)

        // High flatness is more suspicious than very low flatness (peaky = organic harmonic structure).
        return (flatness / FLATNESS_SUSPICION_THRESHOLD).coerceIn(0.0f, 1.0f)
    }

    // ── FFT utilities ────────────────────────────────────────────────────────────

    /**
     * Applies a Hann window to the first [size] samples of [input] (zero-padded if shorter).
     */
    private fun applyHannWindow(input: FloatArray, size: Int): FloatArray {
        val windowed = FloatArray(size)
        for (i in 0 until minOf(input.size, size)) {
            val w = 0.5f * (1.0f - cos(2.0 * PI * i / (size - 1))).toFloat()
            windowed[i] = input[i] * w
        }
        return windowed
    }

    companion object {
        // nextPowerOfTwo(16000/2) = 8192 — covers one 500ms chunk at 16 kHz (8000 samples)
        // Use 4096 to stay inside one chunk without zero-padding overhead
        private const val DEFAULT_FFT_SIZE = 4096

        // Band boundaries (Hz)
        private const val MID_BAND_LOW_HZ = 1000f
        private const val MID_BAND_HIGH_HZ = 4000f
        private const val HIGH_BAND_HZ = 6000f

        // Upper analysis ceiling — the production Nyquist (16 kHz telephony). Frequencies above
        // this are ignored for ALL inputs, so files at higher sample rates are not advantaged.
        private const val ANALYSIS_CEILING_HZ = 8000f

        // If high/mid energy ratio is at or above this, audio is considered organic (full-band).
        private const val ORGANIC_HIGH_MID_RATIO = 0.04f

        // Flatness above this threshold is suspicious (unusually flat/smooth spectrum).
        private const val FLATNESS_SUSPICION_THRESHOLD = 0.6f

        // Blend weights
        private const val WEIGHT_BAND_LIMIT = 0.65f
        private const val WEIGHT_FLATNESS = 0.35f
    }
}
