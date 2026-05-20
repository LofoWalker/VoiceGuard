package com.voiceguard.domain.service
import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import com.voiceguard.rules.NoiseLinearityRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
private fun stableChunk(amplitude: Float, sampleCount: Int = 800) =
    AudioChunk(FloatArray(sampleCount) { amplitude }, sampleRate = 16_000)
private val SPIKE_CHUNK = stableChunk(amplitude = 0.9f)
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DetectionOrchestrator — early-exit and intermittent sampling (Story 2.5)")
class DetectionOrchestratorEarlyExitTest {
    private val dispatcher = UnconfinedTestDispatcher()
    // AC-1: when NoiseLinearityRule signals organic (low suspicion + full confidence),
    //        SpectralArtifactsRule must NOT be invoked for that chunk.
    @Test
    fun `SpectralArtifactsRule_is_skipped_when_NoiseLinearityRule_triggers_early_exit`() = runTest(dispatcher) {
        var spectralInvocations = 0
        val countingSpectralRule = object : AudioDetectionRule {
            override val name = "SpectralArtifactsRule"
            override val weight = 0.35f
            override val isHeavyAnalysis = true
            override val canSkipOnEarlyExit = true
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                spectralInvocations++
                return RuleResult(0.9f, 1.0f)
            }
        }
        // loopSimilarityThreshold=0.99 ensures varied organic chunks never trigger loop suspicion.
        val noiseRule = NoiseLinearityRule(silenceRmsThreshold = 0.001f, loopSimilarityThreshold = 0.99f)
        val orchestrator = DetectionOrchestrator(listOf(noiseRule, countingSpectralRule), dispatcher)
        var phase = 0.0
        fun organicChunk() = AudioChunk(FloatArray(800) {
            phase += 0.37
            (Math.sin(phase) * 0.3 + Math.sin(phase * 2.71) * 0.15).toFloat()
        }, 16_000)
        // 4 chunks ramp NoiseLinearityRule to confidence = 1.0 with low suspicion (organic audio).
        repeat(4) { orchestrator.processChunk(organicChunk()) }
        // 5th chunk: confidence == 1.0 and suspicion <= 0.05 → early-exit fires, spectral skipped.
        spectralInvocations = 0
        orchestrator.processChunk(organicChunk())
        assertEquals(0, spectralInvocations, "SpectralArtifactsRule must be skipped on early-exit")
    }
    // AC-2: during a stable monologue (low RMS variance), only the always-active rule runs;
    //        heavy rules (isHeavyAnalysis = true) are suspended.
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
            override val isEarlyExitTrigger = false
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext) =
                RuleResult(0.5f, 0.5f)
        }
        val orchestrator = DetectionOrchestrator(listOf(alwaysRule, heavyRule), dispatcher)
        val stable = stableChunk(amplitude = 0.15f)
        repeat(5) { orchestrator.processChunk(stable) }
        assertTrue(heavyInvocations < 5,
            "Heavy rule must be suppressed during stable monologue; invocations=$heavyInvocations")
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
        repeat(5) { orchestrator.processChunk(stableChunk(amplitude = 0.15f)) }
        val countBeforeSpike = heavyInvocations
        orchestrator.processChunk(SPIKE_CHUNK)
        assertTrue(heavyInvocations > countBeforeSpike,
            "Heavy rule must be invoked again after energy spike resets intermittent sampling")
    }
    // AC-3: a new speech-turn switch also resumes full analysis.
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
        val orchestrator = DetectionOrchestrator(listOf(alwaysRule, heavyRule), dispatcher)
        repeat(5) { orchestrator.processChunk(stableChunk(0.15f)) }
        val countAfterStable = heavyInvocations
        // Use the internal test helper — avoids brittle reflection on the private context field.
        orchestrator.recordSpeechSwitchForTest(System.currentTimeMillis())
        orchestrator.processChunk(stableChunk(0.15f))
        assertTrue(heavyInvocations > countAfterStable,
            "Heavy rule must resume after a speech-turn switch transition")
    }
}
