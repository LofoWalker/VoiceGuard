package com.voiceguard.domain.model

/**
 * Score pair emitted by every [com.voiceguard.domain.port.AudioDetectionRule] after analyzing one chunk.
 *
 * [suspicionScore] — probability estimate that the audio is AI-generated, in [0.0, 1.0].
 * [confidence] — how reliable this estimate is. A value of 0.0 means "no evidence yet";
 *   the [com.voiceguard.domain.service.ScoreAggregator] must exclude zero-confidence results
 *   from the weighted formula to avoid dividing by zero during the warm-up window (ADR-04).
 */
data class RuleResult(
    val suspicionScore: Float,
    val confidence: Float
)

