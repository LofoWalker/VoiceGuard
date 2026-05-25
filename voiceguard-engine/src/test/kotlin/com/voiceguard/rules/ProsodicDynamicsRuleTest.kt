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

@DisplayName("ProsodicDynamicsRule (R-06)")
class ProsodicDynamicsRuleTest {

    private val context = ConversationContext()

    private fun tone(f0: Double, amplitude: Float, sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(
            FloatArray(sampleRate / 2) { i ->
                val t = i.toDouble() / sampleRate
                amplitude * (sin(2 * PI * f0 * t) + 0.5 * sin(2 * PI * 2 * f0 * t)).toFloat()
            },
            sampleRate
        )

    @Test
    fun `abstains before minimum chunk count`() = runTest {
        val rule = ProsodicDynamicsRule()
        val first = rule.analyze(tone(200.0, 0.8f), context)
        assertEquals(0.0f, first.confidence, "Must abstain until enough chunks are seen")
    }

    @Test
    fun `flat prosody scores higher suspicion than lively prosody`() = runTest {
        // Flat: identical pitch and amplitude every chunk → near-zero variability.
        val flat = ProsodicDynamicsRule()
        var flatResult = flat.analyze(tone(200.0, 0.8f), context)
        repeat(4) { flatResult = flat.analyze(tone(200.0, 0.8f), context) }

        // Lively: pitch and loudness change every chunk → high variability.
        val lively = ProsodicDynamicsRule()
        val pitches = listOf(140.0, 230.0, 160.0, 300.0, 190.0)
        val amps = listOf(0.3f, 0.9f, 0.4f, 1.0f, 0.5f)
        var livelyResult = lively.analyze(tone(pitches[0], amps[0]), context)
        for (i in 1..4) livelyResult = lively.analyze(tone(pitches[i], amps[i]), context)

        assertTrue(flatResult.confidence > 0f && livelyResult.confidence > 0f, "Both must have voted")
        assertTrue(
            flatResult.suspicionScore > livelyResult.suspicionScore,
            "Flat prosody must be more suspicious than lively prosody " +
                    "(flat=${flatResult.suspicionScore}, lively=${livelyResult.suspicionScore})"
        )
    }

    @Test
    fun `suspicion stays in range`() = runTest {
        val rule = ProsodicDynamicsRule()
        var r = rule.analyze(tone(200.0, 0.8f), context)
        repeat(4) { r = rule.analyze(tone(200.0, 0.8f), context) }
        assertTrue(r.suspicionScore in 0.0f..1.0f)
        assertTrue(r.confidence in 0.0f..1.0f)
    }
}
