package com.voiceguard.adapters

import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.port.SpectralClassifierPort

/**
 * Deterministic test double for [SpectralClassifierPort].
 *
 * Consumes a pre-seeded [scores] list in order, then returns [defaultScore] for every subsequent
 * call. Call count is tracked via [callCount] so tests can verify invocation patterns.
 *
 * @param scores  Ordered scores returned for the first N classify() calls.
 * @param defaultScore  Score returned once [scores] is exhausted.
 */
class FakeSpectralClassifier(
    private val scores: List<Float> = emptyList(),
    val defaultScore: Float = 0.0f
) : SpectralClassifierPort {

    var callCount = 0
        private set

    override fun classify(chunk: AudioChunk): Float {
        val score = if (callCount < scores.size) scores[callCount] else defaultScore
        callCount++
        return score
    }

    fun reset() {
        callCount = 0
    }
}

