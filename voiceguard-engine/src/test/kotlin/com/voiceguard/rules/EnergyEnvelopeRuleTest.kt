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

@DisplayName("EnergyEnvelopeRule (VG-021)")
class EnergyEnvelopeRuleTest {

    private val context = ConversationContext()

    // Flat constant-amplitude chunk: envelope is perfectly smooth (zero roughness).
    private fun flatChunk(amplitude: Float = 0.3f, sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(FloatArray(sampleRate / 2) { amplitude }, sampleRate)

    // Chunk with an abrupt envelope change: high-energy first half, silence second half.
    private fun burstChunk(sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(FloatArray(sampleRate / 2) { i ->
            if (i < sampleRate / 4) sin(2 * PI * 200.0 * i / sampleRate).toFloat() else 0f
        }, sampleRate)

    // AC: abstains while fewer than MIN_CHUNKS (3) have been processed.
    @Test
    fun `abstains during warm-up`() = runTest {
        val rule = EnergyEnvelopeRule()
        val chunk = flatChunk()
        val after1 = rule.analyze(chunk, context)
        val after2 = rule.analyze(chunk, context)

        assertEquals(0.0f, after1.confidence, "Must abstain after 1 chunk")
        assertEquals(0.0f, after2.confidence, "Must abstain after 2 chunks")
    }

    // AC: perfectly smooth envelope (TTS-like) scores high suspicion.
    @Test
    fun `smooth flat envelope yields high suspicion`() = runTest {
        val rule = EnergyEnvelopeRule()
        val chunk = flatChunk()
        repeat(5) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.confidence > 0f, "Must have confidence after warm-up")
        assertTrue(result.suspicionScore > 0.5f,
            "Smooth constant-energy envelope must be suspicious: ${result.suspicionScore}")
    }

    // AC: chunk series with varying envelope roughness scores lower than a perfectly flat series.
    @Test
    fun `rough variable envelope is less suspicious than perfectly flat`() = runTest {
        val flatRule = EnergyEnvelopeRule()
        val flat = flatChunk()
        repeat(5) { flatRule.analyze(flat, context) }
        val flatSuspicion = flatRule.analyze(flat, context).suspicionScore

        val burstRule = EnergyEnvelopeRule()
        val burst = burstChunk()
        repeat(5) { burstRule.analyze(burst, context) }
        val burstSuspicion = burstRule.analyze(burst, context).suspicionScore

        assertTrue(burstSuspicion < flatSuspicion,
            "Rough burst envelope must be less suspicious than flat: burst=$burstSuspicion flat=$flatSuspicion")
    }

    // AC: scores stay in [0, 1].
    @Test
    fun `scores are in range after warm-up`() = runTest {
        val rule = EnergyEnvelopeRule()
        val chunk = flatChunk()
        repeat(5) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.suspicionScore in 0.0f..1.0f, "suspicionScore out of range: ${result.suspicionScore}")
        assertTrue(result.confidence in 0.0f..1.0f, "confidence out of range: ${result.confidence}")
    }
}
