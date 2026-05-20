package com.voiceguard.domain.service

import com.voiceguard.adapters.FakeSpectralClassifier
import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import com.voiceguard.rules.NoiseLinearityRule
import com.voiceguard.rules.SpectralArtifactsRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Builds a chunk with constant [amplitude] across [sampleCount] samples at 16 kHz. */
private fun stableChunk(amplitude: Float, sampleCount: Int = 800) =
    AudioChunk(FloatArray(sampleCount) { amplitude }, sampleRate = 16_000)

private val STABLE_ORGANIC_CHUNK = stableChunk(amplitude = 0.15f)   // consistent non-silent, organic
private val SILENT_CHUNK = stableChunk(amplitude = 0.0f)             // digital silence
private val SPIKE_CHUNK = stableChunk(amplitude = 0.9f)              // sudden energy spike

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DetectionOrchestrator — early-exit and intermittent sampling (Story 2.5)")
class DetectionOrchestratorEarlyExitTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // AC-1: when NoiseLinearityRule signals organic (low suspicion + full confidence),
    //        SpectralArtifactsRule must NOT be invoked for that chunk.
    @Test
    fun `SpectralArtifactsRule_is_skipped_when_NoiseLinearityRule_triggers_early_exit`() = runTest(dispatcher) {
        val spectralClassifier = FakeSpectralClassifier(defaultScore = 0.9f)
        var spectralInvocations = 0
        val countingSpectralRule = object : AudioDetectionRule {
            override val name = "SpectralArtifactsRule"
            override val weight = 0.35f
            override val isHeavyAnalysis = true
            override val canSkipOnEarlyExit = true

            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                spectralInvocations++
                return RuleResult(spectralClassifier.classify(chunk), 1.0f)
            }
        }

        // Build a NoiseLinearityRule that reaches full confidence quickly via a very low threshold.
        val noiseRule = NoiseLinearityRule(silenceRmsThreshold = 0.001f, loopSimilarityThreshold = 0.50f)

        // We need NoiseLinearityRule to have isAlwaysActive=true and isEarlyExitTrigger=true
        // (both true by default in the real class). Wrap it to ensure correct capability flags.
        val orchestrator = DetectionOrchestrator(listOf(noiseRule, countingSpectralRule), dispatcher)

        // Feed 4 chunks of organic audio (amplitude=0.15, far above silence threshold).
        // After 4 chunks, NoiseLinearityRule confidence = 1.0.
        // With loopSimilarityThreshold=0.50 and identical chunks, loop suspicion is high...
        // Actually we need LOW suspicion. Use varied organic chunks.

        // Use organic varied audio to get low suspicion + confidence ramp to 1.0.
        var phase = 0.0
        fun organicChunk() = AudioChunk(FloatArray(800) {
            phase += 0.37
            (Math.sin(phase) * 0.3 + Math.sin(phase * 2.71) * 0.15).toFloat()
        }, 16_000)

        // Rebuild with lower loopSimilarityThreshold still but varied chunks.
        val noiseRuleOrganic = NoiseLinearityRule(silenceRmsThreshold = 0.001f, loopSimilarityThreshold = 0.99f)
        val orchestrator2 = DetectionOrchestrator(listOf(noiseRuleOrganic, countingSpectralRule), dispatcher)

        // Feed 4 chunks to ramp NoiseLinearityRule to confidence=1.0.
        // With varied organic audio and loopSimilarityThreshold=0.99, suspicion stays low.
        repeat(4) { orchestrator2.processChunk(organicChunk()) }
        val invocationsAfterWarmup = spectralInvocations

        // 5th chunk: NoiseLinearityRule has confidence=1.0 and low suspicion → early-exit fires.
        spectralInvocations = 0
        orchestrator2.processChunk(organicChunk())

        assertEquals(0, spectralInvocations, "SpectralArtifactsRule must be skipped on early-exit")
    }

    // AC-2: during a stable monologue (low RMS variance), only R-03 (always-active) runs;
    //        heavy rules (R-01, R-02) are suspended.
    @Test
    fun `only_always_active_rule_runs_during_stable_monologue`() = runTest(dispatcher) {
        var heavyInvocations = 0

        val heavyRule = object : AudioDetectionRule {
            override val name = "HeavyRule"
            override val weight = 0.40f
            override val isHeavyAnalysis = true

            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                heavyInvocations++
                return RuleResult(0.5f, 0.5f)
            }
        }

        val alwaysRule = object : AudioDetectionRule {
            override val name = "AlwaysRule"
            override val weight = 0.25f
            override val isAlwaysActive = true
            override val isEarlyExitTrigger = false  // not triggering early-exit

            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext) =
                RuleResult(0.5f, 0.5f)
        }

        val orchestrator = DetectionOrchestrator(listOf(alwaysRule, heavyRule), dispatcher)

        // 4+ chunks with identical stable amplitude → RMS variance = 0, stable monologue detected.
        val stableChunk = stableChunk(amplitude = 0.15f)
        repeat(5) { orchestrator.processChunk(stableChunk) }

        // After 5 identical chunks, the 5th chunk should trigger intermittent sampling suppression.
        // Heavy rule must have been skipped for at least the last chunk.
        // (First 4 fill the window, 5th triggers suppression.)
        assertTrue(heavyInvocations < 5, "Heavy rule must be suppressed during stable monologue; invocations=$heavyInvocations")
    }

    // AC-3: on an energy spike, full analysis resumes.
    @Test
    fun `full_analysis_resumes_after_energy_spike`() = runTest(dispatcher) {
        var heavyInvocations = 0

        val heavyRule = object : AudioDetectionRule {
            override val name = "HeavyRule"
            override val weight = 0.40f
            override val isHeavyAnalysis = true

            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                heavyInvocations++
                return RuleResult(0.5f, 0.5f)
            }
        }

        val alwaysRule = object : AudioDetectionRule {
            override val name = "AlwaysRule"
            override val weight = 0.25f
            override val isAlwaysActive = true

            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext) =
                RuleResult(0.5f, 0.5f)
        }

        val orchestrator = DetectionOrchestrator(listOf(alwaysRule, heavyRule), dispatcher)

        // Establish stable monologue.
        val stableChunk = stableChunk(amplitude = 0.15f)
        repeat(5) { orchestrator.processChunk(stableChunk) }

        // Record heavy invocations BEFORE spike.
        val countBeforeSpike = heavyInvocations

        // Inject a high-energy spike (0.9 amplitude vs 0.15 mean = 6× spike).
        orchestrator.processChunk(SPIKE_CHUNK)

        assertTrue(heavyInvocations > countBeforeSpike,
            "Heavy rule must be invoked again after energy spike resets intermittent sampling")
    }

    // AC-3: speech-turn switch also resumes full analysis.
    @Test
    fun `full_analysis_resumes_after_speech_turn_switch`() = runTest(dispatcher) {
        var heavyInvocations = 0

        val heavyRule = object : AudioDetectionRule {
            override val name = "HeavyRule"
            override val weight = 0.40f
            override val isHeavyAnalysis = true

            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                heavyInvocations++
                return RuleResult(0.5f, 0.5f)
            }
        }

        val alwaysRule = object : AudioDetectionRule {
            override val name = "AlwaysRule"
            override val weight = 0.25f
            override val isAlwaysActive = true

            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext) =
                RuleResult(0.5f, 0.5f)
        }

        // Use reflection to access context and record a speech switch (same approach as existing tests).
        val orchestrator = DetectionOrchestrator(listOf(alwaysRule, heavyRule), dispatcher)

        repeat(5) { orchestrator.processChunk(stableChunk(0.15f)) }
        val countAfterStable = heavyInvocations

        // Simulate a speech-turn switch by recording it via the internal context.
        val contextField = DetectionOrchestrator::class.java.getDeclaredField("context")
        contextField.isAccessible = true
        val ctx = contextField.get(orchestrator) as ConversationContext
        ctx.recordSpeechSwitch(System.currentTimeMillis())

        orchestrator.processChunk(stableChunk(0.15f))

        assertTrue(heavyInvocations > countAfterStable,
            "Heavy rule must resume after a speech-turn switch transition")
    }
}

