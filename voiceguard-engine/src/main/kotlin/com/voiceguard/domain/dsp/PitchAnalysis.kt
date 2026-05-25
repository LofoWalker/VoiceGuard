package com.voiceguard.domain.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Result of an autocorrelation pitch estimate.
 *
 * @param periodSamples  Estimated glottal period in samples (0 when unvoiced).
 * @param f0Hz           Fundamental frequency in Hz (0 when unvoiced).
 * @param voicingStrength Normalised autocorrelation peak in [0, 1]; ~1 = strongly periodic.
 */
data class PitchEstimate(
    val periodSamples: Float,
    val f0Hz: Float,
    val voicingStrength: Float
) {
    val isVoiced: Boolean get() = periodSamples >= 2f && voicingStrength > 0f
}

/** Local cycle-to-cycle perturbation of voiced speech (used for jitter/shimmer). */
data class Perturbation(
    val jitter: Float,   // relative average perturbation of pitch periods (dimensionless)
    val shimmer: Float,  // relative average perturbation of cycle peak amplitudes (dimensionless)
    val cycles: Int
)

/**
 * Pure-JVM pitch / periodicity analysis (autocorrelation), sample-rate aware.
 *
 * All returned perturbation measures are relative ratios, hence invariant to sample rate.
 */
object PitchAnalysis {

    private const val DEFAULT_MIN_HZ = 70f
    private const val DEFAULT_MAX_HZ = 400f
    // Cap the analysis window to bound autocorrelation cost while keeping enough cycles.
    private const val MAX_WINDOW = 4096
    // A shorter-lag peak this close to the global best is preferred (avoids halving F0).
    private const val OCTAVE_PREFER_RATIO = 0.9

    /**
     * Estimates F0 via normalised autocorrelation over [samples] at [sampleRate], searching the
     * [minHz]..[maxHz] band. Returns an unvoiced estimate (zeros) for silence or aperiodic input.
     */
    fun estimatePitch(
        samples: FloatArray,
        sampleRate: Int,
        minHz: Float = DEFAULT_MIN_HZ,
        maxHz: Float = DEFAULT_MAX_HZ
    ): PitchEstimate {
        val n = minOf(samples.size, MAX_WINDOW)
        if (n < 4) return UNVOICED

        val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / minHz).toInt().coerceAtMost(n / 2)
        if (maxLag <= minLag) return UNVOICED

        val corrs = DoubleArray(maxLag - minLag + 1)
        for (lag in minLag..maxLag) {
            var dot = 0.0; var e0 = 0.0; var e1 = 0.0
            for (i in 0 until n - lag) {
                val a = samples[i].toDouble(); val b = samples[i + lag].toDouble()
                dot += a * b; e0 += a * a; e1 += b * b
            }
            val norm = sqrt(e0 * e1)
            corrs[lag - minLag] = if (norm > 1e-12) dot / norm else 0.0
        }

        val globalBest = corrs.maxOrNull() ?: 0.0
        if (globalBest <= 0.0) return UNVOICED

        // Octave-error mitigation: prefer the SHORTEST period whose correlation is within
        // OCTAVE_PREFER_RATIO of the global best, so a 2×-period peak doesn't halve the F0.
        val threshold = OCTAVE_PREFER_RATIO * globalBest
        val chosenIdx = corrs.indexOfFirst { it >= threshold }
        val chosenLag = minLag + chosenIdx
        return PitchEstimate(
            periodSamples = chosenLag.toFloat(),
            f0Hz = sampleRate.toFloat() / chosenLag,
            voicingStrength = corrs[chosenIdx].toFloat().coerceIn(0f, 1f)
        )
    }

    /**
     * Measures local jitter and shimmer by tracking successive glottal-cycle peaks spaced about
     * [periodSamples] apart. Returns zero perturbation when too few cycles are found.
     */
    fun perturbation(samples: FloatArray, periodSamples: Float): Perturbation {
        val peaks = cyclePeaks(samples, periodSamples)
        if (peaks.size < 3) return Perturbation(0f, 0f, peaks.size)

        val intervals = (1 until peaks.size).map { (peaks[it] - peaks[it - 1]).toFloat() }
        val amplitudes = peaks.map { abs(samples[it]) }
        return Perturbation(
            jitter = relativeAveragePerturbation(intervals),
            shimmer = relativeAveragePerturbation(amplitudes),
            cycles = peaks.size
        )
    }

    /**
     * Locates the peak (max |amplitude|) of each successive glottal cycle, anchoring each search
     * window one period after the previous peak so cycle-to-cycle spacing is tracked adaptively.
     */
    private fun cyclePeaks(samples: FloatArray, periodSamples: Float): List<Int> {
        val period = periodSamples.toInt()
        if (period < 2 || samples.size < 2 * period) return emptyList()

        val peaks = ArrayList<Int>()
        var prev = argMaxAbs(samples, 0, period)
        peaks.add(prev)
        while (true) {
            val center = prev + period
            val lo = (center - period / 4).coerceAtLeast(prev + period / 2)
            val hi = (center + period / 4).coerceAtMost(samples.size)
            if (lo >= hi) break
            val next = argMaxAbs(samples, lo, hi)
            peaks.add(next)
            prev = next
        }
        return peaks
    }

    private fun argMaxAbs(samples: FloatArray, from: Int, until: Int): Int {
        var bestIdx = from; var bestVal = abs(samples[from])
        for (i in from until until) {
            val v = abs(samples[i])
            if (v > bestVal) { bestVal = v; bestIdx = i }
        }
        return bestIdx
    }

    /** Mean absolute successive difference divided by the mean — the relative average perturbation. */
    private fun relativeAveragePerturbation(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        if (mean <= 1e-9f) return 0f
        val avgAbsDiff = (1 until values.size)
            .map { abs(values[it] - values[it - 1]) }
            .average().toFloat()
        return avgAbsDiff / mean
    }

    private val UNVOICED = PitchEstimate(0f, 0f, 0f)
}
