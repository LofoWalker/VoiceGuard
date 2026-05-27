package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("JitterShimmerRule (R-04)")
class JitterShimmerRuleTest {

    private val context = ConversationContext()

    private fun regularTone(f0: Double, sampleRate: Int): AudioChunk =
        AudioChunk(
            FloatArray(sampleRate / 2) { i ->
                val t = i.toDouble() / sampleRate
                (sin(2 * PI * f0 * t) + 0.5 * sin(2 * PI * 2 * f0 * t)).toFloat()
            },
            sampleRate
        )

    @Test
    fun `abstains on silence`() = runTest {
        val result = JitterShimmerRule().analyze(AudioChunk(FloatArray(8_000) { 0.0f }), context)
        assertEquals(0.0f, result.confidence, "Silence must yield zero confidence (abstain)")
    }

    @Test
    fun `regular voicing scores high suspicion with non-zero confidence`() = runTest {
        val result = JitterShimmerRule().analyze(regularTone(200.0, 16_000), context)
        assertTrue(result.confidence > 0f, "Voiced chunk must accumulate confidence")
        assertTrue(result.suspicionScore > 0.5f,
            "A perfectly regular voice (no jitter/shimmer) must read as suspicious, got ${result.suspicionScore}")
    }

    @Test
    fun `suspicion is in range 0 to 1`() = runTest {
        val result = JitterShimmerRule().analyze(regularTone(150.0, 16_000), context)
        assertTrue(result.suspicionScore in 0.0f..1.0f)
        assertTrue(result.confidence in 0.0f..1.0f)
    }

    @Test
    fun `suspicion is invariant to sample rate`() = runTest {
        val s16 = JitterShimmerRule().analyze(regularTone(200.0, 16_000), context).suspicionScore
        val s24 = JitterShimmerRule().analyze(regularTone(200.0, 24_000), context).suspicionScore
        assertTrue(abs(s16 - s24) < 0.15f, "Suspicion must be sample-rate invariant: 16k=$s16 vs 24k=$s24")
    }
}
