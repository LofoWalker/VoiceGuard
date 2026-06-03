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

@DisplayName("CodecArtifactRule (VG-023)")
class CodecArtifactRuleTest {

    private val context = ConversationContext()

    private fun sineChunk(freqHz: Double, sampleRate: Int = 16_000): AudioChunk =
        AudioChunk(FloatArray(sampleRate / 2) { i ->
            sin(2 * PI * freqHz * i / sampleRate).toFloat()
        }, sampleRate)

    private fun noiseChunk(sampleRate: Int = 16_000): AudioChunk {
        var x = 0.1
        return AudioChunk(FloatArray(sampleRate / 2) {
            x = (x * 1664525.0 + 1013904223.0) % 4294967296.0
            ((x / 4294967296.0) * 2.0 - 1.0).toFloat()
        }, sampleRate)
    }

    // AC: abstains while fewer than MIN_CHUNKS (3) have been processed.
    @Test
    fun `abstains during warm-up`() = runTest {
        val rule = CodecArtifactRule()
        val chunk = sineChunk(400.0)
        val after1 = rule.analyze(chunk, context)
        val after2 = rule.analyze(chunk, context)

        assertEquals(0.0f, after1.confidence, "Must abstain after 1 chunk")
        assertEquals(0.0f, after2.confidence, "Must abstain after 2 chunks")
    }

    // AC: narrowband signal (all energy below codec cutoff) is more suspicious than wideband noise.
    @Test
    fun `narrowband signal scores higher suspicion than wideband noise`() = runTest {
        val narrowbandRule = CodecArtifactRule()
        val narrowChunk = sineChunk(400.0)
        repeat(5) { narrowbandRule.analyze(narrowChunk, context) }
        val narrowSuspicion = narrowbandRule.analyze(narrowChunk, context).suspicionScore

        val widebandRule = CodecArtifactRule()
        val wideChunk = noiseChunk()
        repeat(5) { widebandRule.analyze(wideChunk, context) }
        val wideSuspicion = widebandRule.analyze(wideChunk, context).suspicionScore

        assertTrue(narrowSuspicion > wideSuspicion,
            "Narrowband signal must be more suspicious than wideband noise: narrow=$narrowSuspicion wide=$wideSuspicion")
    }

    // AC: scores stay in [0, 1].
    @Test
    fun `scores are in range after warm-up`() = runTest {
        val rule = CodecArtifactRule()
        val chunk = sineChunk(400.0)
        repeat(5) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.suspicionScore in 0.0f..1.0f, "suspicionScore out of range: ${result.suspicionScore}")
        assertTrue(result.confidence in 0.0f..1.0f, "confidence out of range: ${result.confidence}")
    }

    // AC: confidence caps at 1.0 after RAMP_CHUNKS (6).
    @Test
    fun `confidence reaches 1 after ramp`() = runTest {
        val rule = CodecArtifactRule()
        val chunk = sineChunk(600.0)
        repeat(6) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertEquals(1.0f, result.confidence, "Confidence must cap at 1.0 after RAMP_CHUNKS")
    }
}
