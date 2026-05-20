package com.voiceguard.domain.model

/**
 * Accumulated detection state emitted to consumers via StateFlow.
 *
 * **Informative-only (PRD Phase 1 / ADR-01):** This type carries analysis signals exclusively.
 * It contains no call-action flags, no termination hooks, and no platform-specific control fields.
 * All three fields are plain numeric scores intended for display and human decision-making only.
 *
 * [globalConfidence] — monotonically non-decreasing evidence gauge in [0.0, 1.0] (ADR-02).
 *   The UI should mute [aiProbability] display when this is below 0.6.
 * [aiProbability] — dynamic verdict in [0.0, 1.0]. Hard-coded to 0.0 while globalConfidence < 0.05
 *   to prevent NaN propagation from an all-zero confidence denominator (ADR-04).
 * [elapsedSeconds] — accumulated processed-audio duration derived from analysed chunks.
 */
data class DetectionUiState(
    val globalConfidence: Float,
    val aiProbability: Float,
    val elapsedSeconds: Float
)

