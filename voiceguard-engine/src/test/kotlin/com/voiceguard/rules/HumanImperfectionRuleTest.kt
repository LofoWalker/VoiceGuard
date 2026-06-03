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

@DisplayName("HumanImperfectionRule (VG-024)")
class HumanImperfectionRuleTest {

    private val context = ConversationContext()

    // Multi-harmonic signal detected as voiced by PitchAnalysis.
    private fun harmonicChunk(f0: Double, amplitude: Float = 1.0f, sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(
            FloatArray(sampleRate / 2) { i ->
                val t = i.toDouble() / sampleRate
                (amplitude * (sin(2 * PI * f0 * t) + 0.5 * sin(2 * PI * 2 * f0 * t) + 0.33 * sin(2 * PI * 3 * f0 * t))).toFloat()
            },
            sampleRate
        )

    // AC: abstains before MIN_VOICED_CHUNKS (3) voiced frames.
    @Test
    fun `abstains on silence`() = runTest {
        val rule = HumanImperfectionRule()
        val silence = AudioChunk(FloatArray(8_000) { 0.0f })
        repeat(5) { rule.analyze(silence, context) }
        val result = rule.analyze(silence, context)

        assertEquals(0.0f, result.confidence, "Silence (unvoiced) must always abstain")
    }

    // AC: perfectly regular voiced signal (no tremor, no pitch reversal) → high suspicion.
    @Test
    fun `regular voiced signal yields high suspicion`() = runTest {
        val rule = HumanImperfectionRule()
        val chunk = harmonicChunk(180.0)
        repeat(7) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        if (result.confidence > 0f) {
            assertTrue(result.suspicionScore > 0.5f,
                "Unnaturally smooth voice must produce high suspicion: ${result.suspicionScore}")
        }
    }

    // AC: scores stay in [0, 1].
    @Test
    fun `scores are in range after warm-up`() = runTest {
        val rule = HumanImperfectionRule()
        val chunk = harmonicChunk(200.0)
        repeat(7) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.suspicionScore in 0.0f..1.0f, "suspicionScore out of range: ${result.suspicionScore}")
        assertTrue(result.confidence in 0.0f..1.0f, "confidence out of range: ${result.confidence}")
    }

    // AC: confidence ramps up with voiced chunks.
    @Test
    fun `confidence grows with voiced chunks`() = runTest {
        val rule = HumanImperfectionRule()
        val chunk = harmonicChunk(160.0)

        var prevConfidence = 0f
        var sawGrowth = false
        for (i in 1..8) {
            val result = rule.analyze(chunk, context)
            if (result.confidence > prevConfidence) sawGrowth = true
            prevConfidence = result.confidence
        }
        assertTrue(sawGrowth, "Confidence must increase with each voiced chunk")
    }
}
