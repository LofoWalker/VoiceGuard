package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlin.math.sqrt

/**
 * VG-019 — Detects suspiciously stable conversational response latencies.
 *
 * Complementary to [LatencyBehaviorRule], which flags absolute latency values in the
 * STT→LLM→TTS pipeline range. This rule instead targets the *consistency* of inter-switch
 * intervals: a human's response times vary naturally due to cognitive load, topic difficulty,
 * and emotional state. An AI pipeline produces suspiciously uniform latencies because the same
 * inference stack runs every turn.
 *
 * Signal: low coefficient of variation (CV) of inter-switch intervals → high suspicion.
 *
 * **Warm-up contract:** returns confidence = 0 until at least two speech-turn switches have been
 * recorded in [ConversationContext] (abstains on utterance-only datasets with no turn-taking).
 */
class TurnTakingLatencyVarianceRule(
    override val weight: Float = 0.50f
) : AudioDetectionRule {

    override val name = "TurnTakingLatencyVarianceRule"
    override val isHeavyAnalysis = true

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val switches = context.speechSwitchTimestamps
        if (switches.size < MIN_SWITCHES) return RuleResult(0f, 0f)

        val intervals = (1 until switches.size).map { (switches[it] - switches[it - 1]).toFloat() }
        val confidence = (intervals.size.toFloat() / RAMP_SWITCHES).coerceAtMost(1f)

        if (intervals.size < 2) return RuleResult(SINGLE_INTERVAL_SUSPICION, confidence)

        val mean = intervals.average().toFloat()
        if (mean <= 0f) return RuleResult(0f, 0f)

        val variance = intervals.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / (intervals.size - 1)
        val cv = sqrt(variance) / mean

        // Low CV → too regular → high suspicion
        val suspicion = (1f - (cv / CV_REF).coerceAtMost(1f)).coerceIn(0f, 1f)
        return RuleResult(suspicion, confidence)
    }

    companion object {
        private const val MIN_SWITCHES = 2
        private const val RAMP_SWITCHES = 6
        private const val SINGLE_INTERVAL_SUSPICION = 0.3f

        // A CV ≥ 0.5 (50% std relative to mean) is considered organically variable.
        // Calibrate on conversational datasets; FoR utterance files will always abstain.
        private const val CV_REF = 0.5f
    }
}
