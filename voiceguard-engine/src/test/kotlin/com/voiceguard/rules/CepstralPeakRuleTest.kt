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

@DisplayName("CepstralPeakRule (R-05)")
class CepstralPeakRuleTest {

    private val context = ConversationContext()

    private fun harmonicChunk(f0: Double, sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(
            FloatArray(sampleRate / 2) { i ->
                val t = i.toDouble() / sampleRate
                (sin(2 * PI * f0 * t) + 0.5 * sin(2 * PI * 2 * f0 * t) + 0.33 * sin(2 * PI * 3 * f0 * t)).toFloat()
            },
            sampleRate
        )

    @Test
    fun `abstains on silence`() = runTest {
        val result = CepstralPeakRule().analyze(AudioChunk(FloatArray(8_000) { 0.0f }), context)
        assertEquals(0.0f, result.confidence, "Silent chunk must yield zero confidence (abstain)")
    }

    @Test
    fun `harmonic signal produces a finite suspicion with non-zero confidence`() = runTest {
        val result = CepstralPeakRule().analyze(harmonicChunk(150.0), context)
        assertTrue(result.confidence > 0f, "Analysed chunk must accumulate confidence")
        assertTrue(!result.suspicionScore.isNaN() && !result.suspicionScore.isInfinite())
        assertTrue(result.suspicionScore in 0.0f..1.0f, "Suspicion must be in [0,1], got ${result.suspicionScore}")
    }

    @Test
    fun `is deterministic on identical input`() = runTest {
        val rule = CepstralPeakRule()
        val a = rule.analyze(harmonicChunk(180.0), context).suspicionScore
        val rule2 = CepstralPeakRule()
        val b = rule2.analyze(harmonicChunk(180.0), context).suspicionScore
        assertEquals(a, b, "Same input must yield the same suspicion")
    }
}
