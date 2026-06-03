package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("RoomResponseRule (VG-022)")
class RoomResponseRuleTest {

    private val context = ConversationContext()

    // All-zero chunk: no tail energy, no LF spectral roughness → anechoic → suspicious.
    private fun silenceChunk(sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(FloatArray(sampleRate / 2) { 0.0f }, sampleRate)

    // White noise: energy spread uniformly → significant tail energy + LF roughness → organic.
    private fun noiseChunk(sampleRate: Int = 16_000): AudioChunk {
        var state = 123456789L
        return AudioChunk(FloatArray(sampleRate / 2) {
            state = state * 6364136223846793005L + 1442695040888963407L
            ((state ushr 33).toFloat() / Int.MAX_VALUE - 1f) * 0.5f
        }, sampleRate)
    }

    // AC: abstains while fewer than MIN_CHUNKS (3) have been processed.
    @Test
    fun `abstains during warm-up`() = runTest {
        val rule = RoomResponseRule()
        val chunk = noiseChunk()
        val after1 = rule.analyze(chunk, context)
        val after2 = rule.analyze(chunk, context)

        assertEquals(0.0f, after1.confidence, "Must abstain after 1 chunk")
        assertEquals(0.0f, after2.confidence, "Must abstain after 2 chunks")
    }

    // AC: silence (no reverb tail, no LF modulation) → high suspicion.
    @Test
    fun `silence yields high suspicion`() = runTest {
        val rule = RoomResponseRule()
        val chunk = silenceChunk()
        repeat(5) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.confidence > 0f, "Must have confidence after warm-up")
        assertTrue(result.suspicionScore > 0.5f,
            "Anechoic-like silence must produce high suspicion: ${result.suspicionScore}")
    }

    // AC: wideband noise (energy in tail, rough LF spectrum) is less suspicious than silence.
    @Test
    fun `noise is less suspicious than silence`() = runTest {
        val silentRule = RoomResponseRule()
        repeat(5) { silentRule.analyze(silenceChunk(), context) }
        val silentSuspicion = silentRule.analyze(silenceChunk(), context).suspicionScore

        val noisyRule = RoomResponseRule()
        repeat(5) { noisyRule.analyze(noiseChunk(), context) }
        val noisySuspicion = noisyRule.analyze(noiseChunk(), context).suspicionScore

        assertTrue(noisySuspicion < silentSuspicion,
            "Noise must be less suspicious than silence: noise=$noisySuspicion silence=$silentSuspicion")
    }

    // AC: scores stay in [0, 1].
    @Test
    fun `scores are in range after warm-up`() = runTest {
        val rule = RoomResponseRule()
        repeat(5) { rule.analyze(noiseChunk(), context) }
        val result = rule.analyze(noiseChunk(), context)

        assertTrue(result.suspicionScore in 0.0f..1.0f, "suspicionScore out of range: ${result.suspicionScore}")
        assertTrue(result.confidence in 0.0f..1.0f, "confidence out of range: ${result.confidence}")
    }
}
