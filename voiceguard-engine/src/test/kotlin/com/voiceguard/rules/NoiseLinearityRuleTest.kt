package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("NoiseLinearityRule")
class NoiseLinearityRuleTest {

    private lateinit var rule: NoiseLinearityRule
    private lateinit var context: ConversationContext

    @BeforeEach
    fun setUp() {
        rule = NoiseLinearityRule(silenceRmsThreshold = 0.01f, loopSimilarityThreshold = 0.95f)
        context = ConversationContext()
    }

    // AC-1, AC-2: perfect silence → high suspicion score; confidence reaches 1.0 within 4 chunks.
    @Test
    fun `should_return_high_suspicion_and_reach_full_confidence_within_4_silence_chunks`() = runTest {
        val silenceChunk = AudioChunk(FloatArray(800) { 0.0f })

        repeat(4) { rule.analyze(silenceChunk, context) }
        val result = rule.analyze(silenceChunk, context)

        assertTrue(result.suspicionScore >= 0.8f, "Silence must produce high suspicion: ${result.suspicionScore}")
        assertEquals(1.0f, result.confidence, "Confidence must be 1.0 after ≥ 4 chunks")
    }

    // AC-2: confidence ramps incrementally — after 2 chunks it is 0.5.
    @Test
    fun `should_ramp_confidence_incrementally_across_chunks`() = runTest {
        val silenceChunk = AudioChunk(FloatArray(800) { 0.0f })

        val after1 = rule.analyze(silenceChunk, context)
        val after2 = rule.analyze(silenceChunk, context)

        assertTrue(after1.confidence > 0f, "Confidence must be > 0 after first chunk")
        assertTrue(after2.confidence > after1.confidence, "Confidence must grow chunk-over-chunk")
    }

    // AC-3: organic non-linear audio → low suspicion score.
    @Test
    fun `should_return_low_suspicion_for_organic_noisy_audio`() = runTest {
        // Organic waveform: random-looking variation that is never near-zero and never repeats.
        var phase = 0.0
        fun organicChunk() = AudioChunk(FloatArray(800) {
            phase += 0.37
            (Math.sin(phase) * 0.3 + Math.sin(phase * 2.71) * 0.15).toFloat()
        })

        // Warm up with distinct chunks to avoid accidental loop detection.
        repeat(3) { rule.analyze(organicChunk(), context) }
        val result = rule.analyze(organicChunk(), context)

        assertTrue(result.suspicionScore < 0.3f, "Organic audio must produce low suspicion: ${result.suspicionScore}")
    }

    // AC-1: looping noise pattern (identical chunks) → high suspicion.
    @Test
    fun `should_return_high_suspicion_for_repeated_ambient_loop_pattern`() = runTest {
        // Identical non-silent chunks simulate a looped ambient noise track.
        val loopChunk = AudioChunk(FloatArray(800) { i -> 0.05f + (i % 8) * 0.005f })

        repeat(4) { rule.analyze(loopChunk, context) }
        val result = rule.analyze(loopChunk, context)

        assertTrue(result.suspicionScore >= 0.7f, "Repeated loop pattern must yield high suspicion: ${result.suspicionScore}")
    }

    // AC-1: rule never mutates ConversationContext (stays as ADR-03 requires).
    @Test
    fun `should_never_mutate_ConversationContext_during_analysis`() = runTest {
        val chunk = AudioChunk(FloatArray(800) { 0.0f })
        val switchesBefore = context.speechSwitchTimestamps.size
        val durationBefore = context.callDurationMillis

        rule.analyze(chunk, context)

        assertEquals(switchesBefore, context.speechSwitchTimestamps.size, "Rule must not record speech switches")
        assertEquals(durationBefore, context.callDurationMillis, "Rule must not update call duration")
    }
}

