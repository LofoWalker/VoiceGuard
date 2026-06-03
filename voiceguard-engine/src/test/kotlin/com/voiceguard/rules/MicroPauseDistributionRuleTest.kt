package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("MicroPauseDistributionRule (VG-017)")
class MicroPauseDistributionRuleTest {

    private val context = ConversationContext()

    private fun silenceChunk(sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(FloatArray(sampleRate / 2) { 0.0f }, sampleRate)

    private fun speechChunk(f0: Double = 180.0, sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(FloatArray(sampleRate / 2) { i ->
            sin(2 * PI * f0 * i / sampleRate).toFloat()
        }, sampleRate)

    // AC: abstains while fewer than MIN_CHUNKS (3) have been processed.
    @Test
    fun `abstains during warm-up`() = runTest {
        val rule = MicroPauseDistributionRule()
        val chunk = silenceChunk()
        val after1 = rule.analyze(chunk, context)
        val after2 = rule.analyze(chunk, context)

        assertEquals(0.0f, after1.confidence, "Must abstain after 1 chunk")
        assertEquals(0.0f, after2.confidence, "Must abstain after 2 chunks")
    }

    // AC: constant all-silence chunks (zero density variance, zero pause duration variance) → high suspicion.
    @Test
    fun `uniform silence distribution yields high suspicion`() = runTest {
        val rule = MicroPauseDistributionRule()
        val chunk = silenceChunk()
        repeat(7) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.confidence > 0f, "Must have confidence after warm-up")
        assertTrue(result.suspicionScore > 0.7f,
            "Perfectly uniform pause distribution must be suspicious: ${result.suspicionScore}")
    }

    // AC: alternating speech/silence chunks produce more density variance → lower suspicion than constant silence.
    @Test
    fun `alternating speech-silence is less suspicious than constant silence`() = runTest {
        val silentRule = MicroPauseDistributionRule()
        repeat(7) { silentRule.analyze(silenceChunk(), context) }
        val silentSuspicion = silentRule.analyze(silenceChunk(), context).suspicionScore

        val variedRule = MicroPauseDistributionRule()
        repeat(4) {
            variedRule.analyze(silenceChunk(), context)
            variedRule.analyze(speechChunk(), context)
        }
        val variedSuspicion = variedRule.analyze(speechChunk(), context).suspicionScore

        assertTrue(variedSuspicion < silentSuspicion,
            "Varied pause pattern must be less suspicious than constant silence: varied=$variedSuspicion silent=$silentSuspicion")
    }

    // AC: scores stay in [0, 1].
    @Test
    fun `scores are in range after warm-up`() = runTest {
        val rule = MicroPauseDistributionRule()
        repeat(7) { rule.analyze(silenceChunk(), context) }
        val result = rule.analyze(silenceChunk(), context)

        assertTrue(result.suspicionScore in 0.0f..1.0f, "suspicionScore out of range: ${result.suspicionScore}")
        assertTrue(result.confidence in 0.0f..1.0f, "confidence out of range: ${result.confidence}")
    }
}
