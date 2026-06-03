package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("SpeechEntropyRule (VG-025)")
class SpeechEntropyRuleTest {

    private val context = ConversationContext()

    // DC signal: amplitude histogram has one bin (max-min ≈ 0) → temporal entropy = 0.
    // FFT: spike at DC → spectral entropy ≈ 0. Both → maximum suspicion.
    private fun dcChunk(value: Float = 0.3f, sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(FloatArray(sampleRate / 2) { value }, sampleRate)

    // White noise: broad amplitude + spectral distribution → high entropy → low suspicion.
    private fun noiseChunk(sampleRate: Int = 16_000): AudioChunk {
        var state = 987654321L
        return AudioChunk(FloatArray(sampleRate / 2) {
            state = state * 6364136223846793005L + 1442695040888963407L
            ((state ushr 33).toFloat() / Int.MAX_VALUE - 1f) * 0.5f
        }, sampleRate)
    }

    // AC: abstains while fewer than MIN_CHUNKS (3) have been processed.
    @Test
    fun `abstains during warm-up`() = runTest {
        val rule = SpeechEntropyRule()
        val chunk = dcChunk()
        val after1 = rule.analyze(chunk, context)
        val after2 = rule.analyze(chunk, context)

        assertEquals(0.0f, after1.confidence, "Must abstain after 1 chunk")
        assertEquals(0.0f, after2.confidence, "Must abstain after 2 chunks")
    }

    // AC: zero-variance DC signal (minimal entropy) → high suspicion.
    @Test
    fun `low-entropy DC signal yields high suspicion`() = runTest {
        val rule = SpeechEntropyRule()
        val chunk = dcChunk()
        repeat(5) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.confidence > 0f, "Must have confidence after warm-up")
        assertTrue(result.suspicionScore > 0.5f,
            "Low-entropy DC signal must be suspicious: ${result.suspicionScore}")
    }

    // AC: white noise (high entropy) is less suspicious than a DC signal.
    @Test
    fun `high-entropy noise is less suspicious than DC signal`() = runTest {
        val dcRule = SpeechEntropyRule()
        repeat(5) { dcRule.analyze(dcChunk(), context) }
        val dcSuspicion = dcRule.analyze(dcChunk(), context).suspicionScore

        val noiseRule = SpeechEntropyRule()
        repeat(5) { noiseRule.analyze(noiseChunk(), context) }
        val noiseSuspicion = noiseRule.analyze(noiseChunk(), context).suspicionScore

        assertTrue(noiseSuspicion < dcSuspicion,
            "High-entropy noise must be less suspicious than DC: noise=$noiseSuspicion dc=$dcSuspicion")
    }

    // AC: scores stay in [0, 1].
    @Test
    fun `scores are in range after warm-up`() = runTest {
        val rule = SpeechEntropyRule()
        repeat(5) { rule.analyze(noiseChunk(), context) }
        val result = rule.analyze(noiseChunk(), context)

        assertTrue(result.suspicionScore in 0.0f..1.0f, "suspicionScore out of range: ${result.suspicionScore}")
        assertTrue(result.confidence in 0.0f..1.0f, "confidence out of range: ${result.confidence}")
    }

    // AC: confidence caps at 1.0 after RAMP_CHUNKS (6).
    @Test
    fun `confidence reaches 1 after ramp`() = runTest {
        val rule = SpeechEntropyRule()
        repeat(6) { rule.analyze(noiseChunk(), context) }
        val result = rule.analyze(noiseChunk(), context)

        assertEquals(1.0f, result.confidence, "Confidence must cap at 1.0 after RAMP_CHUNKS")
    }
}
