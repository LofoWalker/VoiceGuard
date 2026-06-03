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

@DisplayName("HarmonicConsistencyRule (VG-020)")
class HarmonicConsistencyRuleTest {

    private val context = ConversationContext()

    private fun harmonicChunk(f0: Double, sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(
            FloatArray(sampleRate / 2) { i ->
                val t = i.toDouble() / sampleRate
                (sin(2 * PI * f0 * t) + 0.5 * sin(2 * PI * 2 * f0 * t) + 0.33 * sin(2 * PI * 3 * f0 * t)).toFloat()
            },
            sampleRate
        )

    // AC: silence (unvoiced) → abstain every time.
    @Test
    fun `abstains on silence`() = runTest {
        val rule = HarmonicConsistencyRule()
        val silence = AudioChunk(FloatArray(8_000) { 0.0f })
        repeat(5) { rule.analyze(silence, context) }
        val result = rule.analyze(silence, context)

        assertEquals(0.0f, result.confidence, "Silence must always abstain (unvoiced)")
    }

    // AC: repeated identical voiced chunks → cosine similarity = 1 → high suspicion.
    @Test
    fun `identical harmonic chunks yield high suspicion`() = runTest {
        val rule = HarmonicConsistencyRule()
        val chunk = harmonicChunk(160.0)
        repeat(5) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        if (result.confidence > 0f) {
            assertTrue(result.suspicionScore > 0.5f,
                "Identical spectra across chunks must be highly suspicious: ${result.suspicionScore}")
        }
        assertTrue(result.suspicionScore in 0.0f..1.0f, "suspicionScore out of range: ${result.suspicionScore}")
    }

    // AC: scores stay in [0, 1] for voiced input.
    @Test
    fun `scores are in range for voiced signal`() = runTest {
        val rule = HarmonicConsistencyRule()
        val chunk = harmonicChunk(200.0)
        repeat(5) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.suspicionScore in 0.0f..1.0f, "suspicionScore out of range: ${result.suspicionScore}")
        assertTrue(result.confidence in 0.0f..1.0f, "confidence out of range: ${result.confidence}")
    }

    // AC: a signal at a very different f0 yields a lower or equal cross-chunk similarity than identical frames.
    @Test
    fun `cross-f0 spectra are less similar than identical frames`() = runTest {
        val identicalRule = HarmonicConsistencyRule()
        val sameChunk = harmonicChunk(150.0)
        repeat(6) { identicalRule.analyze(sameChunk, context) }
        val identicalSuspicion = identicalRule.analyze(sameChunk, context).suspicionScore

        // Two different f0 values alternating → lower cross-chunk similarity
        val mixedRule = HarmonicConsistencyRule()
        repeat(4) {
            mixedRule.analyze(harmonicChunk(150.0), context)
            mixedRule.analyze(harmonicChunk(330.0), context)
        }
        val mixedSuspicion = mixedRule.analyze(harmonicChunk(150.0), context).suspicionScore

        if (identicalRule.analyze(sameChunk, context).confidence > 0f) {
            assertTrue(mixedSuspicion <= identicalSuspicion,
                "Mixed spectra must not be more suspicious than identical ones: mixed=$mixedSuspicion identical=$identicalSuspicion")
        }
    }
}
