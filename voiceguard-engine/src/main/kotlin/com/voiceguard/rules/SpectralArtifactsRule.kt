package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import com.voiceguard.domain.port.SpectralClassifierPort

/**
 * R-02 — Detects vocoder-like spectral artifacts via an injected local classifier.
 *
 * Targeted synthesis signatures: ElevenLabs, OpenAI TTS, Kokoro — quantization scars,
 * band-limited harmonics, and periodic spectral tiling that distinguishes synthetic speech
 * from natural human phonation.
 *
 * Confidence grows linearly across successive chunks via a simple ramp over [RAMP_CHUNKS],
 * reflecting incremental evidence accumulation. A [SpectralClassifierPort] is accepted via
 * constructor injection so the domain layer stays free of Android/native bindings (ADR-01):
 *
 * - Production: [com.voiceguard.adapters.FftSpectralClassifier]
 * - Tests:      [com.voiceguard.adapters.FakeSpectralClassifier]
 *
 * This rule is [isHeavyAnalysis] and [canSkipOnEarlyExit] because it is the most CPU-expensive
 * step and the first candidate to be deferred when R-03 already confirms organic human audio.
 *
 * @param classifier The spectral-classification backend — injected at construction.
 * @param invertScore When true, the classifier's suspicion is inverted (1 − score). Calibrated on
 *   the FoR validation split, where the band-limiting premise is reversed: the synthetic ("fake")
 *   voices are high-quality and *brighter* than the human reads, so a full/bright high band is the
 *   AI cue here, not a band-limited one. AUC on validation: 0.31 raw → ~0.69 inverted.
 */
class SpectralArtifactsRule(
    private val classifier: SpectralClassifierPort,
    private val invertScore: Boolean = false,
    override val weight: Float = 0.15f
) : AudioDetectionRule {

    override val name = "SpectralArtifactsRule"
    override val isHeavyAnalysis = true
    override val canSkipOnEarlyExit = true

    private var chunkCount = 0

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val raw = classifier.classify(chunk)
        val score = if (invertScore) 1.0f - raw else raw
        chunkCount++
        val confidence = (chunkCount.toFloat() / RAMP_CHUNKS).coerceAtMost(1.0f)
        return RuleResult(score, confidence)
    }

    companion object {
        // Ramp over ~3 seconds (6 × 500 ms chunks) to reach full confidence.
        // Tuned to typical dataset-utterance lengths (2–3 s): a 10-chunk ramp left R-02
        // stuck near confidence ~0.4 on short files, capping globalConfidence below the gate.
        private const val RAMP_CHUNKS = 6
    }
}

