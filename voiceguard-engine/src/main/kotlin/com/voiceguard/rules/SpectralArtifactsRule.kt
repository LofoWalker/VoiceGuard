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
 * constructor injection so the domain layer stays free of Android/TFLite bindings (ADR-01):
 *
 * - Production: [com.voiceguard.adapters.TFLiteSpectralAdapter]
 * - Tests:      [com.voiceguard.adapters.FakeSpectralClassifier]
 *
 * This rule is [isHeavyAnalysis] and [canSkipOnEarlyExit] because it is the most CPU-expensive
 * step and the first candidate to be deferred when R-03 already confirms organic human audio.
 *
 * @param classifier The spectral-classification backend — injected at construction.
 */
class SpectralArtifactsRule(
    private val classifier: SpectralClassifierPort
) : AudioDetectionRule {

    override val name = "SpectralArtifactsRule"
    override val weight = 0.35f
    override val isHeavyAnalysis = true
    override val canSkipOnEarlyExit = true

    private var chunkCount = 0

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val score = classifier.classify(chunk)
        chunkCount++
        val confidence = (chunkCount.toFloat() / RAMP_CHUNKS).coerceAtMost(1.0f)
        return RuleResult(score, confidence)
    }

    companion object {
        // Ramp over ~5 seconds (10 × 500 ms chunks) to reach full confidence.
        private const val RAMP_CHUNKS = 10
    }
}

