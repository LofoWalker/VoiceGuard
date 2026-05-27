package com.voiceguard.domain.service

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.DetectionUiState
import com.voiceguard.domain.model.RuleDiagnostic
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * **Rule dispatch strategy (Story 2.5):**
 * - Phase 1 always runs [AudioDetectionRule.isAlwaysActive] rules (R-03 NoiseLinearityRule).
 * - Early-exit: if an [AudioDetectionRule.isEarlyExitTrigger] result signals strong organic
 *   evidence (`suspicionScore < 0.05`, `confidence == 1.0`), rules marked
 *   [AudioDetectionRule.canSkipOnEarlyExit] (R-02 SpectralArtifactsRule) are skipped.
 * - Intermittent sampling: if the 2-second RMS variance window is below [RMS_VARIANCE_THRESHOLD]
 *   and no transition is detected, all [AudioDetectionRule.isHeavyAnalysis] rules (R-01 + R-02)
 *   are suspended. Full analysis resumes automatically on silence, speech-turn, or RMS spike.
 *
 * **Monotone confidence (ADR-02):** [DetectionUiState.globalConfidence] advances via a
 * one-way ratchet — [peakConfidence] is never allowed to decrease.
 *
 * **Thread-safety:** External callers may invoke [processChunk] concurrently; execution is
 * serialized with an internal mutex so elapsed-time and confidence state remain coherent.
 *
 * @param rules      Detection rules injected at construction — evaluated each chunk.
 * @param dispatcher Coroutine dispatcher for rule execution. Supply
 *                   [kotlinx.coroutines.test.UnconfinedTestDispatcher] in unit tests.
 * @param aggregator Stateless scoring service; defaults to [ScoreAggregator].
 */
class DetectionOrchestrator(
    private val rules: List<AudioDetectionRule>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val aggregator: ScoreAggregator = ScoreAggregator()
) {
    private val totalRuleWeight = rules.sumOf { it.weight.toDouble() }.toFloat()

    private val context = ConversationContext()

    private val _state = MutableStateFlow(DetectionUiState(0.0f, 0.0f, 0.0f))

    /** Read-only live detection state; collectors receive an update after every processed chunk. */
    val state: StateFlow<DetectionUiState> = _state.asStateFlow()

    private var totalElapsedMicros = 0L
    private var peakConfidence = 0.0f
    private val processingMutex = Mutex()

    // --- Intermittent-sampling state (Story 2.5) ---
    // Sliding window of per-chunk RMS values over the last RMS_WINDOW_SIZE chunks (≈ 2 s).
    private val rmsWindow = ArrayDeque<Float>(RMS_WINDOW_SIZE + 1)
    private var previousSwitchCount = 0
    private var previousWindowMeanRms = 0f
    private var previousChunkWasSilent = false

    // Set by detectTransition() when a silence→speech crossing is detected.
    // Consumed in processChunk() after totalElapsedMicros is updated (ADR-03).
    private var speechSwitchPending = false

    // Per-rule observability: latest result each rule produced + which rules ran last chunk.
    // Rules suppressed on the last chunk keep their previous result but report activeOnLastChunk=false.
    private val lastResultByRule = HashMap<String, RuleResult>()
    private var lastActiveRuleNames: Set<String> = emptySet()

    /**
     * Analyses one [AudioChunk], then emits an updated [DetectionUiState].
     *
     * Processing order per chunk:
     * 1. Compute RMS; evaluate transition detection (speech turn, silence, energy spike).
     * 2. Phase 1: always-active rules (R-03) execute in parallel.
     * 3. Early-exit evaluation: skip [AudioDetectionRule.canSkipOnEarlyExit] rules if triggered.
     * 4. Intermittent-sampling evaluation: skip all [AudioDetectionRule.isHeavyAnalysis] rules
     *    when the audio segment is stable and no transition is pending.
     * 5. Phase 2: remaining rules execute in parallel.
     * 6. [context] is mutated **only after** all rules complete (ADR-03).
     * 7. Scoring, monotone ratchet, and warm-up gate are applied before [StateFlow] emission.
     */
    suspend fun processChunk(chunk: AudioChunk) = processingMutex.withLock {
        coroutineScope {
            val chunkDurationMicros = (chunk.pcmData.size.toLong() * 1_000_000L) / chunk.sampleRate
            val chunkRms = computeRms(chunk.pcmData)

            val transitionDetected = detectTransition(chunkRms)
            updateRmsWindow(chunkRms)
            val stableMonologue = !transitionDetected && isStableMonologue()

            // Phase 1: always-active rules (e.g. R-03 NoiseLinearityRule) run unconditionally.
            val alwaysActiveResults = rules
                .filter { it.isAlwaysActive }
                .map { rule -> async(dispatcher) { rule to rule.analyze(chunk, context) } }
                .awaitAll()

            // Early-exit: triggered when R-03 is fully confident the audio is organic human.
            val earlyExitActive = alwaysActiveResults
                .filter { (rule, _) -> rule.isEarlyExitTrigger }
                .any { (_, result) ->
                    // Use <= so ORGANIC_SUSPICION (0.05f) == threshold triggers early-exit.
                    result.suspicionScore <= EARLY_EXIT_SUSPICION_THRESHOLD && result.confidence == 1.0f
                }

            // Phase 2: non-always-active rules, subject to suppression policy.
            val phase2Results = rules
                .filter { !it.isAlwaysActive }
                .filter { rule ->
                    when {
                        // Intermittent sampling: suspend all heavy rules during stable monologue.
                        stableMonologue && rule.isHeavyAnalysis -> false
                        // Early-exit: skip spectral rule when R-03 confirms organic audio.
                        earlyExitActive && rule.canSkipOnEarlyExit -> false
                        else -> true
                    }
                }
                .map { rule -> async(dispatcher) { rule to rule.analyze(chunk, context) } }
                .awaitAll()

            val allResults = alwaysActiveResults + phase2Results

            val contributions = allResults.map { (rule, result) ->
                RuleContribution(rule.weight, result.suspicionScore, result.confidence)
            }

            // Record per-rule diagnostics for observability (rules that ran this chunk).
            allResults.forEach { (rule, result) -> lastResultByRule[rule.name] = result }
            lastActiveRuleNames = allResults.map { (rule, _) -> rule.name }.toSet()

            // Context mutations strictly after all rules complete (ADR-03).
            totalElapsedMicros += chunkDurationMicros
            context.updateCallDuration(totalElapsedMicros / 1_000L)
            // Record speech switch (detected before rules ran) using the audio-timeline timestamp
            // so LatencyBehaviorRule measures real inter-turn intervals, not wall-clock drift.
            if (speechSwitchPending) {
                context.recordSpeechSwitch(totalElapsedMicros / 1_000L)
            }

            val rawConfidence = aggregator.computeRawConfidence(contributions, totalRuleWeight)
            peakConfidence = maxOf(peakConfidence, rawConfidence)

            val globalConfidence = if (totalElapsedMicros < 1_000_000L) 0.0f else peakConfidence
            val aiProbability = if (globalConfidence < 0.05f) 0.0f
            else aggregator.computeAiProbability(contributions)

            _state.value = DetectionUiState(globalConfidence, aiProbability, totalElapsedMicros / 1_000_000f)
        }
    }

    private fun updateRmsWindow(rms: Float) {
        if (rmsWindow.size >= RMS_WINDOW_SIZE) rmsWindow.removeFirst()
        rmsWindow.addLast(rms)
        previousWindowMeanRms = if (rmsWindow.isEmpty()) 0f else rmsWindow.average().toFloat()
    }

    /**
     * Returns true when the last [RMS_WINDOW_SIZE] chunks show low variance and no transition
     * is pending — the audio is a stable monologue segment safe to sample intermittently.
     */
    private fun isStableMonologue(): Boolean {
        if (rmsWindow.size < RMS_WINDOW_SIZE) return false
        val mean = rmsWindow.average()
        val variance = rmsWindow.sumOf { v -> val d = v - mean; d * d } / rmsWindow.size
        return variance < RMS_VARIANCE_THRESHOLD
    }

    /**
     * Detects audio transitions that should resume full analysis:
     * - New speech-turn switch recorded in context.
     * - RMS energy spike (current chunk significantly louder than the recent window mean).
     * - Silence boundary crossing (silent→non-silent or non-silent→silent).
     *
     * Only state *changes* on the silence axis trigger a transition event — sustained silence
     * does not trigger repeatedly, allowing intermittent-sampling to apply during quiet segments.
     *
     * Updates [previousSwitchCount] and [previousChunkWasSilent] as side effects.
     */
    private fun detectTransition(currentRms: Float): Boolean {
        val currentSwitchCount = context.speechSwitchTimestamps.size
        val speechTurnDetected = currentSwitchCount > previousSwitchCount
        previousSwitchCount = currentSwitchCount

        val energySpike = previousWindowMeanRms > 0f &&
                currentRms > previousWindowMeanRms * RMS_SPIKE_FACTOR

        // Trigger only on silence-state crossings, not on every silent chunk.
        val wasSilent = previousChunkWasSilent
        val currentlySilent = currentRms < SILENCE_RMS_THRESHOLD
        val silenceBoundaryCrossing = currentlySilent != wasSilent
        previousChunkWasSilent = currentlySilent

        // A silence→speech crossing signals a new speaker turn.
        // The switch is recorded in processChunk() after totalElapsedMicros is updated
        // so the stored timestamp reflects the audio-timeline position (C-2).
        speechSwitchPending = wasSilent && !currentlySilent

        return speechTurnDetected || energySpike || silenceBoundaryCrossing
    }

    companion object {
        // Early-exit: skip spectral analysis when R-03 is fully confident audio is organic.
        private const val EARLY_EXIT_SUSPICION_THRESHOLD = 0.05f

        // Intermittent-sampling: 4 chunks × 500 ms = 2-second sliding variance window.
        private const val RMS_WINDOW_SIZE = 4

        // Below this variance the audio segment is classified as a stable monologue.
        private const val RMS_VARIANCE_THRESHOLD = 0.001f

        // An RMS jump of 3× the window mean is treated as an energy spike.
        private const val RMS_SPIKE_FACTOR = 3.0f

        // RMS below this value = silence boundary event.
        private const val SILENCE_RMS_THRESHOLD = 0.01f
    }

    /**
     * Returns the latest per-rule diagnostic for every configured rule, in declaration order.
     *
     * For each rule it reports the most recent suspicion/confidence it produced for this stream and
     * whether it ran on the last chunk (false ⇒ suppressed by early-exit or intermittent sampling).
     * A rule that has never run yet reports suspicion 0, confidence 0, inactive.
     */
    fun ruleDiagnostics(): List<RuleDiagnostic> = rules.map { rule ->
        val last = lastResultByRule[rule.name]
        RuleDiagnostic(
            ruleName = rule.name,
            weight = rule.weight,
            suspicionScore = last?.suspicionScore ?: 0.0f,
            confidence = last?.confidence ?: 0.0f,
            activeOnLastChunk = rule.name in lastActiveRuleNames
        )
    }

    /**
     * Test-only helper: records a speech-switch timestamp in the orchestrator's internal context.
     *
     * Using `internal` access avoids brittle reflection. `ConversationContext.recordSpeechSwitch`
     * is already `internal` so this wrapper is reachable from the `:voiceguard-engine` test
     * source set without widening any public API.
     */
    internal fun recordSpeechSwitchForTest(epochMillis: Long) {
        context.recordSpeechSwitch(epochMillis)
    }
}
