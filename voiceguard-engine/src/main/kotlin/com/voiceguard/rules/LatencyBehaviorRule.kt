package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule

/**
 * R-01 — Detects suspicious response latency after speech-turn switches.
 *
 * An STT→LLM→TTS cloud pipeline introduces a characteristic delay of 1.5–2.2 s after the human
 * stops speaking. Human biological reaction time varies naturally in the 180–350 ms range.
 * Narrow clustering of inter-switch intervals in the AI range is the primary behavioural signal.
 *
 * **Warm-up contract:** returns `confidence = 0.0` until at least one speech-turn switch has been
 * recorded in [ConversationContext] (ADR exclusion: zero-confidence rules are excluded from the
 * weighted formula by [com.voiceguard.domain.service.ScoreAggregator]).
 *
 * This rule is [isHeavyAnalysis] = true because it requires conversation history and is
 * suppressible during stable monologues where no turn-switching occurs.
 */
class LatencyBehaviorRule : AudioDetectionRule {

    override val name = "LatencyBehaviorRule"
    override val weight = 0.40f
    override val isHeavyAnalysis = true

    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
        val switches = context.speechSwitchTimestamps

        if (switches.isEmpty()) {
            return RuleResult(suspicionScore = 0.0f, confidence = 0.0f)
        }

        // Confidence grows with each observed speech switch; saturates at CONFIDENCE_SATURATION_SWITCHES.
        val confidence = (switches.size.toFloat() / CONFIDENCE_SATURATION_SWITCHES).coerceAtMost(1.0f)

        if (switches.size < 2) {
            // Single switch: evidence exists but no interval can be measured yet.
            return RuleResult(suspicionScore = AMBIGUOUS_SUSPICION, confidence = confidence)
        }

        val intervals = (1 until switches.size).map { i -> switches[i] - switches[i - 1] }
        val suspicion = scoreIntervals(intervals)

        return RuleResult(suspicion, confidence)
    }

    /**
     * Scores a list of inter-switch intervals in milliseconds.
     *
     * Delays clustered in the STT→LLM→TTS range (1 500–2 200 ms) are strongly suspicious.
     * Delays in the human biological range (180–350 ms) are scored low.
     */
    private fun scoreIntervals(intervals: List<Long>): Float {
        val scores = intervals.map { ms ->
            when {
                ms in AI_LATENCY_LOW..AI_LATENCY_HIGH -> AI_LATENCY_SUSPICION
                ms in HUMAN_LATENCY_LOW..HUMAN_LATENCY_HIGH -> HUMAN_LATENCY_SUSPICION
                else -> AMBIGUOUS_SUSPICION
            }
        }
        return scores.average().toFloat()
    }

    companion object {
        private const val CONFIDENCE_SATURATION_SWITCHES = 5

        // STT→LLM→TTS round-trip window (architecture §R-01)
        private const val AI_LATENCY_LOW = 1500L
        private const val AI_LATENCY_HIGH = 2200L
        private const val AI_LATENCY_SUSPICION = 0.9f

        // Human biological reaction range
        private const val HUMAN_LATENCY_LOW = 180L
        private const val HUMAN_LATENCY_HIGH = 350L
        private const val HUMAN_LATENCY_SUSPICION = 0.1f

        private const val AMBIGUOUS_SUSPICION = 0.4f
    }
}

