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
 * @property isAlwaysActive If true, the orchestrator never skips this rule — not subject to
 *   early-exit or intermittent-sampling suppression.
 * @property isEarlyExitTrigger If true, the orchestrator evaluates an early-exit condition on
 *   this rule's result to potentially skip expensive analysis downstream.
 * @property isHeavyAnalysis If true, the orchestrator may skip this rule during intermittent
 *   sampling (stable monologue) or early-exit suppression.
 * @property canSkipOnEarlyExit If true, this rule is specifically skipped when an early-exit
 *   trigger fires (subset of [isHeavyAnalysis]).
 */
interface AudioDetectionRule {

    val name: String
    val weight: Float

    val isAlwaysActive: Boolean get() = false
    val isEarlyExitTrigger: Boolean get() = false
    val isHeavyAnalysis: Boolean get() = false
    val canSkipOnEarlyExit: Boolean get() = false

    /**
     * Analyses a single 500 ms PCM chunk and returns a suspicion/confidence pair.
     *
     * Implementations must never return null, never throw, and never write to [context].
     * Return [RuleResult] with confidence = 0.0 to signal "insufficient data — exclude me."
     */
    suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult
}
