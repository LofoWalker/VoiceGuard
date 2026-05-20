package com.voiceguard.domain.port

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult

/**
 * Primary port for voice-detection rules.
 *
 * Every concrete rule implements this interface and is injected into the DetectionOrchestrator.
 * Rules are dispatched concurrently via `async { rule.analyze(chunk, context) }` — they must
 * therefore be coroutine-safe and must NOT mutate [ConversationContext] (ADR-03).
 *
 * @property name   Human-readable identifier used in logging and metrics.
 * @property weight Contribution factor in the weighted scoring formula, in [0.0, 1.0].
 */
interface AudioDetectionRule {

    /** Human-readable identifier used in logging and metrics. */
    val name: String

    /** Contribution factor in the weighted scoring formula, in [0.0, 1.0]. */
    val weight: Float

    /**
     * Analyses a single 500 ms PCM chunk and returns a suspicion/confidence pair.
     *
     * Implementations must never return null, never throw, and never write to [context].
     * Return [RuleResult] with confidence = 0.0 to signal "insufficient data — exclude me."
     */
    suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult
}

