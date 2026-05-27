package com.voiceguard.harness

/**
 * Centralised weight configuration for the five production detection rules.
 *
 * Weights do **not** need to sum to 1.0 — [com.voiceguard.domain.service.ScoreAggregator]
 * normalises by the weighted-confidence denominator automatically, so only the ratios matter.
 *
 * This data class is the **single source of truth** for rule weights.  Both
 * [ValidationRunner] and [WeightSweepRunner] construct rules from it, so changing
 * weights never requires editing multiple files.
 *
 * @param noiseLinearity    Weight for R-03 — [com.voiceguard.rules.NoiseLinearityRule].
 * @param spectralArtifacts Weight for R-02 — [com.voiceguard.rules.SpectralArtifactsRule].
 * @param jitterShimmer     Weight for R-04 — [com.voiceguard.rules.JitterShimmerRule].
 * @param cepstralPeak      Weight for R-05 — [com.voiceguard.rules.CepstralPeakRule].
 * @param prosodicDynamics  Weight for R-06 — [com.voiceguard.rules.ProsodicDynamicsRule].
 */
data class RuleWeightConfig(
    val noiseLinearity: Float    = DEFAULT_NOISE_LINEARITY,
    val spectralArtifacts: Float = DEFAULT_SPECTRAL_ARTIFACTS,
    val jitterShimmer: Float     = DEFAULT_JITTER_SHIMMER,
    val cepstralPeak: Float      = DEFAULT_CEPSTRAL_PEAK,
    val prosodicDynamics: Float  = DEFAULT_PROSODIC_DYNAMICS
) {
    /** Sum of all configured weights — useful for normalisation diagnostics. */
    val total: Float
        get() = noiseLinearity + spectralArtifacts + jitterShimmer + cepstralPeak + prosodicDynamics

    /**
     * Compact human-readable representation used in sweep reports.
     *
     * Example: `NL=0.20 SA=0.15 JS=0.20 CP=0.15 PD=0.15`
     */
    override fun toString(): String =
        "NL=${"%.2f".format(noiseLinearity)} " +
        "SA=${"%.2f".format(spectralArtifacts)} " +
        "JS=${"%.2f".format(jitterShimmer)} " +
        "CP=${"%.2f".format(cepstralPeak)} " +
        "PD=${"%.2f".format(prosodicDynamics)}"

    companion object {
        // ── Calibrated defaults — sweepWeights run 2025-05-27 ─────────────────
        // Dataset : FoR testing split (2370 fake, 2264 real — 4634 files)
        // Objectif: recall max avec FPR ≤ 5%   seed=42, nRuns=25, step=0.05
        // Résultats: acc=76.2% (+2.5 pp), FPR=4.8% (+1.5 pp), recall=57.9% (+6.4 pp)
        // Précédents : NL=0.20 SA=0.15 JS=0.20 CP=0.15 PD=0.15 → acc=73.7%, FPR=3.3%, recall=51.5%
        const val DEFAULT_NOISE_LINEARITY:    Float = 0.95f
        const val DEFAULT_SPECTRAL_ARTIFACTS: Float = 0.25f
        const val DEFAULT_JITTER_SHIMMER:     Float = 0.05f
        const val DEFAULT_CEPSTRAL_PEAK:      Float = 0.35f
        const val DEFAULT_PROSODIC_DYNAMICS:  Float = 0.70f

        /**
         * Production weights calibrated via [WeightSweepRunner] on the FoR testing split.
         * These are the values used by [ValidationRunner] in normal operation and serve as
         * the baseline in every [WeightSweepRunner] report.
         *
         * To recalibrate, run:
         * ```bash
         * ./gradlew :voiceguard-engine:sweepWeights -PdatasetPath=<dir> -PnRuns=50
         * ```
         * and copy the snippet from the RECOMMANDATION section into the constants above.
         */
        val PRODUCTION = RuleWeightConfig()
    }
}
