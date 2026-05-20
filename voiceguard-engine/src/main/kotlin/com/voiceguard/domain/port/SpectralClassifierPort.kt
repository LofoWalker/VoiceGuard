package com.voiceguard.domain.port

import com.voiceguard.domain.model.AudioChunk

/**
 * Secondary port for spectral voice classification.
 *
 * Decouples [com.voiceguard.rules.SpectralArtifactsRule] from any concrete inference backend
 * (TFLite, ONNX, or a test double) so the domain layer stays pure JVM/Kotlin (ADR-01).
 *
 * Production adapter: [com.voiceguard.adapters.TFLiteSpectralAdapter].
 * Test double:        [com.voiceguard.adapters.FakeSpectralClassifier].
 */
fun interface SpectralClassifierPort {

    /**
     * Classifies a single audio chunk and returns an AI-vocoder probability score in [0.0, 1.0].
     *
     * 0.0 = confident human voice; 1.0 = confident synthetic/vocoder artifact.
     * The implementation must be pure and side-effect free — no mutable state visible outside.
     */
    fun classify(chunk: AudioChunk): Float
}

