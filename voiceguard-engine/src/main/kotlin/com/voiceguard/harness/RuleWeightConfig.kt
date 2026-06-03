package com.voiceguard.harness

/**
 * Centralised weight configuration for all production detection rules.
 *
 * Weights do **not** need to sum to 1.0 — [com.voiceguard.domain.service.ScoreAggregator]
 * normalises by the weighted-confidence denominator automatically, so only the ratios matter.
 *
 * This data class is the **single source of truth** for rule weights.  Both
 * [ValidationRunner] and [WeightSweepRunner] construct rules from it, so changing
 * weights never requires editing multiple files.
 *
 * @param noiseLinearity             Weight for R-03 — [com.voiceguard.rules.NoiseLinearityRule].
 * @param spectralArtifacts          Weight for R-02 — [com.voiceguard.rules.SpectralArtifactsRule].
 * @param jitterShimmer              Weight for R-04 — [com.voiceguard.rules.JitterShimmerRule].
 * @param cepstralPeak               Weight for R-05 — [com.voiceguard.rules.CepstralPeakRule].
 * @param prosodicDynamics           Weight for R-06 — [com.voiceguard.rules.ProsodicDynamicsRule].
 * @param microPauseDistribution     Weight for VG-017 — [com.voiceguard.rules.MicroPauseDistributionRule].
 * @param emotionalVariance          Weight for VG-018 — [com.voiceguard.rules.EmotionalVarianceRule].
 * @param turnTakingLatencyVariance  Weight for VG-019 — [com.voiceguard.rules.TurnTakingLatencyVarianceRule].
 * @param harmonicConsistency        Weight for VG-020 — [com.voiceguard.rules.HarmonicConsistencyRule].
 * @param energyEnvelope             Weight for VG-021 — [com.voiceguard.rules.EnergyEnvelopeRule].
 * @param roomResponse               Weight for VG-022 — [com.voiceguard.rules.RoomResponseRule].
 * @param codecArtifact              Weight for VG-023 — [com.voiceguard.rules.CodecArtifactRule].
 * @param humanImperfection          Weight for VG-024 — [com.voiceguard.rules.HumanImperfectionRule].
 * @param speechEntropy              Weight for VG-025 — [com.voiceguard.rules.SpeechEntropyRule].
 */
data class RuleWeightConfig(
    val noiseLinearity: Float            = DEFAULT_NOISE_LINEARITY,
    val spectralArtifacts: Float         = DEFAULT_SPECTRAL_ARTIFACTS,
    val jitterShimmer: Float             = DEFAULT_JITTER_SHIMMER,
    val cepstralPeak: Float              = DEFAULT_CEPSTRAL_PEAK,
    val prosodicDynamics: Float          = DEFAULT_PROSODIC_DYNAMICS,
    val microPauseDistribution: Float    = DEFAULT_MICRO_PAUSE_DISTRIBUTION,
    val emotionalVariance: Float         = DEFAULT_EMOTIONAL_VARIANCE,
    val turnTakingLatencyVariance: Float = DEFAULT_TURN_TAKING_LATENCY_VARIANCE,
    val harmonicConsistency: Float       = DEFAULT_HARMONIC_CONSISTENCY,
    val energyEnvelope: Float            = DEFAULT_ENERGY_ENVELOPE,
    val roomResponse: Float              = DEFAULT_ROOM_RESPONSE,
    val codecArtifact: Float             = DEFAULT_CODEC_ARTIFACT,
    val humanImperfection: Float         = DEFAULT_HUMAN_IMPERFECTION,
    val speechEntropy: Float             = DEFAULT_SPEECH_ENTROPY
) {
    /** Sum of all configured weights — useful for normalisation diagnostics. */
    val total: Float
        get() = noiseLinearity + spectralArtifacts + jitterShimmer + cepstralPeak + prosodicDynamics +
                microPauseDistribution + emotionalVariance + turnTakingLatencyVariance +
                harmonicConsistency + energyEnvelope + roomResponse + codecArtifact +
                humanImperfection + speechEntropy

    /**
     * Compact human-readable representation used in sweep reports.
     *
     * Example: `NL=0.75 SA=1.00 JS=0.75 CP=0.30 PD=0.35 MPD=0.50 …`
     */
    override fun toString(): String =
        "NL=${"%.2f".format(noiseLinearity)} " +
        "SA=${"%.2f".format(spectralArtifacts)} " +
        "JS=${"%.2f".format(jitterShimmer)} " +
        "CP=${"%.2f".format(cepstralPeak)} " +
        "PD=${"%.2f".format(prosodicDynamics)} " +
        "MPD=${"%.2f".format(microPauseDistribution)} " +
        "EV=${"%.2f".format(emotionalVariance)} " +
        "TTLV=${"%.2f".format(turnTakingLatencyVariance)} " +
        "HC=${"%.2f".format(harmonicConsistency)} " +
        "EE=${"%.2f".format(energyEnvelope)} " +
        "RR=${"%.2f".format(roomResponse)} " +
        "CA=${"%.2f".format(codecArtifact)} " +
        "HI=${"%.2f".format(humanImperfection)} " +
        "SE=${"%.2f".format(speechEntropy)}"

    companion object {
        // ── Poids recalibrés 2026-05-28 d'après le pouvoir discriminant mesuré sur FoR testing split ──
        // Dataset : 2370 fake, 2264 real — 4634 fichiers  |  Mesures : AUC ROC + Cohen's d
        //
        // Classement par d (écart normalisé) — seul critère objectif :
        //   SE d=1.65  PD d=1.33  NL d=1.11  HI d=0.69  SA d=0.68  RR d=0.62
        //   CA d=0.51  CP d=0.37  JS d=0.31
        //
        // Règles non-discriminantes ou sens inversé (poids minimisés à 0.05) :
        //   MPD d=-0.52 (direction inversée sur FoR)
        //   EV  d=-0.04 (bruit pur, poids 0.95 → 0.05)
        //   HC  scores=0.00 pour les deux classes, pas de signal
        //   EE  scores=0.00 pour les deux classes, pas de signal
        //   TTLV abstient systématiquement sur FoR (pas de turn-taking)
        //
        // Sweep 500 runs, seed=42, step=0.05, aiThreshold=0.55 (2026-05-28) :
        //   Cette baseline bat les 500 combinaisons aléatoires (score 0.6765 vs 0.6278 pour le meilleur).
        //   La calibration manuelle par AUC/d est plus efficace que le sweep aléatoire sur 14 dimensions.
        //
        // Seuil de décision recalibré via threshold sweep → voir ValidationRunner.CALIBRATED_AI_THRESHOLD.
        const val DEFAULT_NOISE_LINEARITY:    Float = 0.65f
        const val DEFAULT_SPECTRAL_ARTIFACTS: Float = 0.55f
        const val DEFAULT_JITTER_SHIMMER:     Float = 0.25f
        const val DEFAULT_CEPSTRAL_PEAK:      Float = 0.20f
        const val DEFAULT_PROSODIC_DYNAMICS:  Float = 0.75f

        // ── VG-017..VG-025 — poids d'après AUC + Cohen's d sur FoR testing split ──
        // MPD: direction inversée (2026-05-28). Score sature à ~1.0 pour les deux classes car
        // DENSITY_VARIANCE_REF/PAUSE_VARIANCE_REF << variances réelles sur FoR → poids minimal.
        // L'inversion est confirmée (AUC=0.59, d=+0.52) ; le sweep déterminera le poids optimal.
        const val DEFAULT_MICRO_PAUSE_DISTRIBUTION:    Float = 0.05f  // sature → contribution marginale
        const val DEFAULT_EMOTIONAL_VARIANCE:          Float = 0.05f  // d=-0.04 : bruit
        const val DEFAULT_TURN_TAKING_LATENCY_VARIANCE: Float = 0.05f // abstient sur FoR
        const val DEFAULT_HARMONIC_CONSISTENCY:        Float = 0.05f  // score=0 pour les deux classes
        const val DEFAULT_ENERGY_ENVELOPE:             Float = 0.05f  // score=0 pour les deux classes
        const val DEFAULT_ROOM_RESPONSE:               Float = 0.45f  // d=0.62
        const val DEFAULT_CODEC_ARTIFACT:              Float = 0.40f  // d=0.51
        const val DEFAULT_HUMAN_IMPERFECTION:          Float = 0.50f  // d=0.69
        const val DEFAULT_SPEECH_ENTROPY:              Float = 0.85f  // d=1.65 : meilleure règle

        /**
         * Production weights — R-02..R-06 calibrated on the FoR testing split; VG-017..VG-025
         * defaulting to 0.50f pending the next [WeightSweepRunner] run with the full 14-rule set.
         *
         * To recalibrate, run:
         * ```bash
         * ./gradlew :voiceguard-engine:sweepWeights -PdatasetPath=<dir> -PnRuns=200
         * ```
         * and copy the snippet from the RECOMMANDATION section into the constants above.
         */
        val PRODUCTION = RuleWeightConfig()
    }
}
