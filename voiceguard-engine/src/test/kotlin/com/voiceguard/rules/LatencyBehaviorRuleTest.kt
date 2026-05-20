package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("LatencyBehaviorRule")
class LatencyBehaviorRuleTest {

    private lateinit var rule: LatencyBehaviorRule
    private lateinit var context: ConversationContext
    private val chunk = AudioChunk(FloatArray(800) { 0.1f })

    @BeforeEach
    fun setUp() {
        rule = LatencyBehaviorRule()
        context = ConversationContext()
    }

    // AC-1: no speech switches recorded → confidence == 0.0 (warm-up exclusion).
    @Test
    fun `should_return_zero_confidence_before_any_speech_switch`() = runTest {
        val result = rule.analyze(chunk, context)

        assertEquals(0.0f, result.confidence, "Must return confidence=0 with no speech turns")
        assertEquals(0.0f, result.suspicionScore, "Suspicion must also be 0 with no evidence")
    }

    // AC-2: repeated delays in the 1.5–2.2 s AI pipeline range → high suspicion score.
    @Test
    fun `should_score_high_suspicion_for_repeated_ai_pipeline_delays`() = runTest {
        val baseTime = System.currentTimeMillis()
        // 5 switches ~1.8 s apart: consistent STT→LLM→TTS round-trip timing.
        val aiTimes = listOf(0L, 1800L, 3600L, 5400L, 7200L).map { baseTime + it }
        aiTimes.forEach { context.recordSpeechSwitch(it) }

        val result = rule.analyze(chunk, context)

        assertTrue(result.suspicionScore >= 0.7f, "AI-range delays must produce high suspicion: ${result.suspicionScore}")
        assertTrue(result.confidence > 0f, "Confidence must be > 0 after speech switches")
    }

    // AC-3: variable human-range delays (200–350 ms) → low suspicion score.
    @Test
    fun `should_score_low_suspicion_for_human_variable_response_delays`() = runTest {
        val baseTime = System.currentTimeMillis()
        // 5 switches with biologically natural variability in 200–350 ms range.
        val humanTimes = listOf(0L, 250L, 520L, 780L, 1050L).map { baseTime + it }
        humanTimes.forEach { context.recordSpeechSwitch(it) }

        val result = rule.analyze(chunk, context)

        assertTrue(result.suspicionScore < 0.3f, "Human-range delays must produce low suspicion: ${result.suspicionScore}")
    }

    // AC-2: confidence grows with each additional speech switch.
    @Test
    fun `should_increase_confidence_with_each_additional_speech_switch`() = runTest {
        val baseTime = System.currentTimeMillis()

        context.recordSpeechSwitch(baseTime)
        val confidenceAfter1 = rule.analyze(chunk, context).confidence

        context.recordSpeechSwitch(baseTime + 1800L)
        val confidenceAfter2 = rule.analyze(chunk, context).confidence

        assertTrue(confidenceAfter2 > confidenceAfter1, "Confidence must grow with more speech switches")
    }

    // AC-3: rule never mutates ConversationContext.
    @Test
    fun `should_never_mutate_ConversationContext_during_analysis`() = runTest {
        val baseTime = System.currentTimeMillis()
        context.recordSpeechSwitch(baseTime)
        context.recordSpeechSwitch(baseTime + 1800L)

        val switchCountBefore = context.speechSwitchTimestamps.size
        rule.analyze(chunk, context)

        assertEquals(switchCountBefore, context.speechSwitchTimestamps.size, "Rule must not add speech switches")
    }
}

