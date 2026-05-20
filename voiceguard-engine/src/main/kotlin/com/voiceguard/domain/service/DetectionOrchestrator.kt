package com.voiceguard.domain.service

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.DetectionUiState
import com.voiceguard.domain.port.AudioDetectionRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central orchestrator for real-time AI voice detection.
 *
 * **Informative-only (ADR-01 / PRD Phase 1):** This engine exposes analysis signals only.
 * It never terminates, blocks, or interrupts calls. All decisions remain with the human operator.
 *
 * **Concurrency contract (ADR-03):** Rules execute in parallel via [async]/[awaitAll].
 * The [ConversationContext] is read-only to rules during their analysis window.
 * All mutations to [context] happen strictly after [awaitAll] to guarantee rules observe
 * a consistent, stable snapshot of conversation state within a given chunk cycle.
 *
 * **Monotone confidence (ADR-02):** [DetectionUiState.globalConfidence] advances via a
 * one-way ratchet — [peakConfidence] is never allowed to decrease, preventing gauge oscillation.
 *
 * @param rules      Detection rules injected at construction — evaluated in parallel each chunk.
 * @param dispatcher Coroutine dispatcher for rule execution. Supply
 *                   [kotlinx.coroutines.test.UnconfinedTestDispatcher] in unit tests for
 *                   deterministic, synchronous flow behavior.
 * @param aggregator Stateless scoring service; defaults to [ScoreAggregator].
 */
class DetectionOrchestrator(
    private val rules: List<AudioDetectionRule>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val aggregator: ScoreAggregator = ScoreAggregator()
) {

    private val context = ConversationContext()

    private val _state = MutableStateFlow(DetectionUiState(0.0f, 0.0f, 0.0f))

    /** Read-only live detection state; collectors receive an update after every processed chunk. */
    val state: StateFlow<DetectionUiState> = _state.asStateFlow()

    // Cumulative elapsed time derived from actual chunk durations, not wall-clock time.
    private var totalElapsedSeconds = 0.0f

    // ADR-02: one-way ratchet — globalConfidence never decreases across chunks.
    private var peakConfidence = 0.0f

    /**
     * Analyses one [AudioChunk], then emits an updated [DetectionUiState].
     *
     * Processing order per chunk:
     * 1. All rules launched in parallel via [async] on [dispatcher].
     * 2. [awaitAll] collects every [com.voiceguard.domain.model.RuleResult].
     * 3. [context] is mutated **only after** all rules complete (ADR-03).
     * 4. [ScoreAggregator] computes raw confidence and AI probability.
     * 5. Monotone ratchet and warm-up gate are applied before [StateFlow] emission.
     */
    suspend fun processChunk(chunk: AudioChunk) = coroutineScope {
        val chunkDuration = chunk.pcmData.size.toFloat() / chunk.sampleRate

        // Rules receive the current context snapshot — no mutations happen during analysis.
        val ruleResults = rules
            .map { rule -> async(dispatcher) { rule to rule.analyze(chunk, context) } }
            .awaitAll()

        // Context mutations strictly after all rules complete (ADR-03).
        totalElapsedSeconds += chunkDuration
        context.updateCallDuration((totalElapsedSeconds * 1000).toLong())

        val contributions = ruleResults.map { (rule, result) ->
            RuleContribution(rule.weight, result.suspicionScore, result.confidence)
        }

        val rawConfidence = aggregator.computeRawConfidence(contributions)
        peakConfidence = maxOf(peakConfidence, rawConfidence)

        // Warm-up gate: first second suppressed to avoid unreliable early reads.
        val globalConfidence = if (totalElapsedSeconds < 1.0f) 0.0f else peakConfidence

        // ADR-04: NaN guard delegated to aggregator; also suppressed during warm-up.
        val aiProbability = if (globalConfidence < 0.05f) 0.0f
        else aggregator.computeAiProbability(contributions)

        _state.value = DetectionUiState(globalConfidence, aiProbability, totalElapsedSeconds)
    }
}

