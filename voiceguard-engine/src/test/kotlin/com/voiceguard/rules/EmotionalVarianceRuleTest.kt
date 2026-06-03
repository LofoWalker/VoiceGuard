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

@DisplayName("EmotionalVarianceRule (VG-018)")
class EmotionalVarianceRuleTest {

    private val context = ConversationContext()

    private fun sineChunk(amplitude: Float, f0: Double = 180.0, sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(FloatArray(sampleRate / 2) { i ->
            (amplitude * sin(2 * PI * f0 * i / sampleRate)).toFloat()
        }, sampleRate)

    // AC: abstains while fewer than MIN_CHUNKS (5) chunks AND localRmsVariances < 2 (need windowSize+1).
    @Test
    fun `abstains during warm-up`() = runTest {
        val rule = EmotionalVarianceRule()
        val chunk = sineChunk(0.5f)
        // After 4 chunks: rmsHistory.size=4 < MIN_CHUNKS(5) → still abstains
        repeat(3) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertEquals(0.0f, result.confidence, "Must abstain after 4 chunks (below MIN_CHUNKS=5)")
    }

    // AC: constant-amplitude chunks (no emotional dynamics) produce high suspicion.
    @Test
    fun `constant amplitude yields high suspicion`() = runTest {
        val rule = EmotionalVarianceRule()
        val chunk = sineChunk(0.5f)
        repeat(9) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.confidence > 0f, "Must have confidence after enough chunks")
        assertTrue(result.suspicionScore > 0.5f,
            "Constant amplitude (no emotional variance) must be suspicious: ${result.suspicionScore}")
    }

    // AC: chunks with alternating amplitudes (organic expressiveness) are less suspicious.
    @Test
    fun `varying amplitude yields lower suspicion than constant amplitude`() = runTest {
        val constantRule = EmotionalVarianceRule()
        val constantChunk = sineChunk(0.5f)
        repeat(9) { constantRule.analyze(constantChunk, context) }
        val constantSuspicion = constantRule.analyze(constantChunk, context).suspicionScore

        val variedRule = EmotionalVarianceRule()
        val amplitudes = listOf(0.1f, 0.8f, 0.05f, 0.9f, 0.2f, 0.7f, 0.03f, 0.85f, 0.15f, 0.75f)
        amplitudes.forEachIndexed { i, amp -> variedRule.analyze(sineChunk(amp), context) }

        assertTrue(variedRule.analyze(sineChunk(0.5f), context).suspicionScore < constantSuspicion,
            "Variable amplitude must be less suspicious than constant amplitude")
    }

    // AC: scores stay in [0, 1].
    @Test
    fun `scores are in range after warm-up`() = runTest {
        val rule = EmotionalVarianceRule()
        val chunk = sineChunk(0.4f)
        repeat(9) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.suspicionScore in 0.0f..1.0f, "suspicionScore out of range: ${result.suspicionScore}")
        assertTrue(result.confidence in 0.0f..1.0f, "confidence out of range: ${result.confidence}")
    }
}
