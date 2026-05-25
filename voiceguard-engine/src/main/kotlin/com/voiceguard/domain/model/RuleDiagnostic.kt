package com.voiceguard.domain.model

/**
 * Per-rule diagnostic snapshot exposed for observability (validation harness, debugging).
 *
 * Carries the most recent result a single rule produced for the current audio stream. Unlike
 * [DetectionUiState] (which is the aggregated, display-facing signal), this type is intended for
 * inspecting *why* the engine reached a verdict — which rule contributed what.
 *
 * @param ruleName          Stable identifier from [com.voiceguard.domain.port.AudioDetectionRule.name].
 * @param weight            Rule weight in the scoring formula.
 * @param suspicionScore    Latest suspicion in [0.0, 1.0] (0 = human-like, 1 = AI-like).
 * @param confidence        Latest confidence in [0.0, 1.0]; 0 means the rule is not voting yet.
 * @param activeOnLastChunk False when the rule was suppressed on the last chunk by early-exit or
 *                          intermittent sampling — its [suspicionScore]/[confidence] are then carried
 *                          over from the most recent chunk on which it actually ran.
 */
data class RuleDiagnostic(
    val ruleName: String,
    val weight: Float,
    val suspicionScore: Float,
    val confidence: Float,
    val activeOnLastChunk: Boolean
)
