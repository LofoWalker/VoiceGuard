package com.voiceguard.domain.service

import com.voiceguard.domain.model.RuleResult

/**
 * Immutable input to the scoring formula — one entry per evaluated rule per chunk.
 *
 * Decouples [ScoreAggregator] from the [com.voiceguard.domain.port.AudioDetectionRule] port
 * so the aggregator operates on plain numerics with no port dependency.
 */
data class RuleContribution(
    val weight: Float,
    val suspicionScore: Float,
    val confidence: Float
)

/**
 * Stateless service that applies the confidence-aware weighted scoring formula.
 *
 * Scoring formula (architecture § Scoring Formula):
 * ```
 * aiProbability = Σ(Score_i × Weight_i × Confidence_i) / Σ(Weight_i × Confidence_i)
 * ```
 *
 * The denominator is always checked for zero before division (ADR-04) and the result
 * is guarded against NaN/Infinite to guarantee safe downstream consumption.
 *
 * All state (previous confidence peak for the monotone ratchet) is maintained by the caller.
 */
class ScoreAggregator {

    /**
     * Computes the weighted AI probability from the given contributions.
     *
     * Rules with [RuleResult.confidence] == 0.0 are implicitly excluded because they
     * contribute zero to both the numerator and denominator.
     *
     * Returns `0.0f` instead of `Float.NaN` when the denominator is zero (ADR-04).
     */
    fun computeAiProbability(contributions: List<RuleContribution>): Float {
        val denominator = contributions.sumOf { (it.weight * it.confidence).toDouble() }
        if (denominator == 0.0) return 0.0f

        val numerator = contributions.sumOf {
            (it.weight * it.confidence * it.suspicionScore).toDouble()
        }
        val result = (numerator / denominator).toFloat()
        return if (result.isNaN() || result.isInfinite()) 0.0f else result
    }

    /**
     * Computes a weighted average of confidences across all contributions.
     *
     * Used by the orchestrator as the raw input to the monotone confidence ratchet.
     * Returns `0.0f` when total weight is zero.
     */
    fun computeRawConfidence(contributions: List<RuleContribution>): Float {
        val totalWeight = contributions.sumOf { it.weight.toDouble() }
        if (totalWeight == 0.0) return 0.0f

        val result = (contributions.sumOf {
            (it.weight * it.confidence).toDouble()
        } / totalWeight).toFloat()
        return if (result.isNaN() || result.isInfinite()) 0.0f else result
    }
}

