package com.voiceguard.domain.service

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.DetectionUiState
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Builds a chunk whose duration is [durationSeconds] when processed at the standard 16 kHz rate.
 * Using 2 Hz lets us create fractional-second chunks (e.g. 0.5 s = 1 sample at 2 Hz) without
 * allocating thousands of floats in unit tests.
 */
private fun chunkOf(durationSeconds: Float, sampleRate: Int = 2): AudioChunk {
    val sampleCount = (durationSeconds * sampleRate).toInt().coerceAtLeast(1)
    return AudioChunk(FloatArray(sampleCount) { 0.1f }, sampleRate)
}

private val HALF_SECOND_CHUNK = chunkOf(0.5f)

/**
 * Stub rule with configurable output — never throws, never mutates context (ADR-03).
 */
private class FakeAudioDetectionRule(
    override val name: String = "Fake",
    override val weight: Float = 1.0f,
    private val result: RuleResult = RuleResult(suspicionScore = 0.8f, confidence = 0.9f)
) : AudioDetectionRule {
    override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult = result
}

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionOrchestratorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // AC-1: StateFlow receives an updated DetectionUiState after each processChunk call.
    @Test
    fun `processChunk emits DetectionUiState via StateFlow`() = runTest(dispatcher) {
        val rule = FakeAudioDetectionRule(result = RuleResult(0.8f, 0.9f))
        val orchestrator = DetectionOrchestrator(listOf(rule), dispatcher)

        orchestrator.processChunk(HALF_SECOND_CHUNK)

        // StateFlow was updated (at least once) — value is not the seed state
        val state = orchestrator.state.value
        assertTrue(state.elapsedSeconds > 0f, "elapsedSeconds must advance after first chunk")
    }

    // AC-1: The emitted state type is correct and fields are populated.
    @Test
    fun `emitted state contains non-negative probability and elapsed fields`() = runTest(dispatcher) {
        val rule = FakeAudioDetectionRule(result = RuleResult(0.5f, 0.8f))
        val orchestrator = DetectionOrchestrator(listOf(rule), dispatcher)

        // Submit 3 half-second chunks (totalElapsed = 1.5 s — past warm-up)
        repeat(3) { orchestrator.processChunk(HALF_SECOND_CHUNK) }

        val state = orchestrator.state.value
        assertTrue(state.aiProbability in 0f..1f, "aiProbability must be in [0, 1]")
        assertTrue(state.globalConfidence in 0f..1f, "globalConfidence must be in [0, 1]")
        assertTrue(state.elapsedSeconds > 0f, "elapsedSeconds must be positive")
    }

    // AC-2: During warm-up (elapsed < 1.0 s) globalConfidence is forced to 0.0.
    @Test
    fun `globalConfidence is 0 during warm-up period (first second)`() = runTest(dispatcher) {
        // Rule returns a high confidence so we can verify the gate suppresses it.
        val rule = FakeAudioDetectionRule(result = RuleResult(0.9f, 1.0f))
        val orchestrator = DetectionOrchestrator(listOf(rule), dispatcher)

        // One 0.5-second chunk — elapsed = 0.5 s → still in warm-up
        orchestrator.processChunk(HALF_SECOND_CHUNK)

        assertEquals(
            0.0f, orchestrator.state.value.globalConfidence,
            "globalConfidence must be 0.0 during warm-up"
        )
    }

    // AC-2: aiProbability is also suppressed (0.0) while in warm-up.
    @Test
    fun `aiProbability is 0 during warm-up period`() = runTest(dispatcher) {
        val rule = FakeAudioDetectionRule(result = RuleResult(0.9f, 1.0f))
        val orchestrator = DetectionOrchestrator(listOf(rule), dispatcher)

        orchestrator.processChunk(HALF_SECOND_CHUNK)

        assertEquals(0.0f, orchestrator.state.value.aiProbability, "aiProbability must be 0.0 during warm-up")
    }

    // AC-2: After the first second the gate lifts and globalConfidence becomes non-zero.
    @Test
    fun `globalConfidence becomes non-zero after warm-up period ends`() = runTest(dispatcher) {
        val rule = FakeAudioDetectionRule(result = RuleResult(0.8f, 0.8f))
        val orchestrator = DetectionOrchestrator(listOf(rule), dispatcher)

        // Two 0.5-second chunks → elapsed = 1.0 s → warm-up over
        repeat(2) { orchestrator.processChunk(HALF_SECOND_CHUNK) }

        assertTrue(
            orchestrator.state.value.globalConfidence > 0f,
            "globalConfidence must exceed 0 once warm-up ends"
        )
    }

    // AC-3: elapsedSeconds increases monotonically with each submitted chunk.
    @Test
    fun `elapsedSeconds advances correctly after multiple chunks`() = runTest(dispatcher) {
        val orchestrator = DetectionOrchestrator(listOf(FakeAudioDetectionRule()), dispatcher)

        val snapshots = mutableListOf<Float>()
        repeat(4) {
            orchestrator.processChunk(HALF_SECOND_CHUNK)
            snapshots += orchestrator.state.value.elapsedSeconds
        }

        // Each chunk must add exactly 0.5 s
        for (i in snapshots.indices) {
            val expected = (i + 1) * 0.5
            assertEquals(expected, snapshots[i].toDouble(), 0.001, "elapsedSeconds mismatch after chunk ${i + 1}")
        }
    }

    // AC-3: A single chunk advances elapsed by exactly its own duration.
    @Test
    fun `single chunk advances elapsedSeconds by its own duration`() = runTest(dispatcher) {
        val orchestrator = DetectionOrchestrator(listOf(FakeAudioDetectionRule()), dispatcher)
        val oneSecondChunk = chunkOf(1.0f)

        orchestrator.processChunk(oneSecondChunk)

        assertEquals(1.0, orchestrator.state.value.elapsedSeconds.toDouble(), 0.001)
    }

    // ADR-04: All-zero confidence denominator must not produce NaN — aiProbability stays 0.
    @Test
    fun `aiProbability is 0 when all rule confidences are zero (NaN guard)`() = runTest(dispatcher) {
        val zeroConfidenceRule = FakeAudioDetectionRule(result = RuleResult(0.9f, 0.0f))
        val orchestrator = DetectionOrchestrator(listOf(zeroConfidenceRule), dispatcher)

        // Bypass warm-up by submitting enough chunks (2 × 0.5 s = 1.0 s)
        repeat(2) { orchestrator.processChunk(HALF_SECOND_CHUNK) }

        assertEquals(0.0f, orchestrator.state.value.aiProbability, "NaN guard must yield 0.0 not NaN")
    }

    // C-2: VAD records a speech switch on silence→speech crossing using audio-timeline timestamp.
    // The switch is recorded AFTER rules run (ADR-03), so it becomes visible on the next chunk.
    @Test
    fun `silence-to-speech crossing records speech switch visible to rules on next chunk`() = runTest(dispatcher) {
        val capturedSwitchCounts = mutableListOf<Int>()
        val spyRule = object : AudioDetectionRule {
            override val name = "SwitchSpy"
            override val weight = 1.0f
            override val isAlwaysActive = true
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                capturedSwitchCounts += context.speechSwitchTimestamps.size
                return RuleResult(0.0f, 0.0f)
            }
        }
        val orchestrator = DetectionOrchestrator(listOf(spyRule), dispatcher)

        // sampleRate=2 → each 1-sample chunk = 0.5s of audio.
        val nonSilent = AudioChunk(FloatArray(1) { 0.5f }, sampleRate = 2)  // RMS=0.5 > 0.01 threshold
        val silent    = AudioChunk(FloatArray(1) { 0.0f }, sampleRate = 2)  // RMS=0.0 < 0.01 threshold

        orchestrator.processChunk(nonSilent)  // chunk 1: non-silent, no prior state → no crossing
        orchestrator.processChunk(silent)     // chunk 2: non-silent→silent, no switch recorded
        orchestrator.processChunk(nonSilent)  // chunk 3: silent→non-silent crossing; switch recorded AFTER rules run
        orchestrator.processChunk(nonSilent)  // chunk 4: spy now sees the recorded switch

        // Spy sees no switches during chunks 1–3 (switch recorded after chunk-3 rules complete)
        // Spy sees exactly 1 switch during chunk 4
        assertEquals(listOf(0, 0, 0, 1), capturedSwitchCounts,
            "Speech switch must be invisible during crossing chunk and visible on the next one")
    }

    // C-2: Switch timestamp uses audio elapsed time, not wall-clock time.
    // After 3 half-second chunks (1.5 s of audio) the timestamp must equal 1500 ms.
    @Test
    fun `speech switch timestamp reflects audio elapsed time not wall clock`() = runTest(dispatcher) {
        val capturedTimestamps = mutableListOf<Long>()
        val spyRule = object : AudioDetectionRule {
            override val name = "TimestampSpy"
            override val weight = 1.0f
            override val isAlwaysActive = true
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                capturedTimestamps += context.speechSwitchTimestamps.lastOrNull() ?: -1L
                return RuleResult(0.0f, 0.0f)
            }
        }
        val orchestrator = DetectionOrchestrator(listOf(spyRule), dispatcher)

        // sampleRate=2, 1 sample = 0.5 s per chunk
        val nonSilent = AudioChunk(FloatArray(1) { 0.5f }, sampleRate = 2)
        val silent    = AudioChunk(FloatArray(1) { 0.0f }, sampleRate = 2)

        orchestrator.processChunk(nonSilent)   // chunk 1 — 0.5 s elapsed
        orchestrator.processChunk(silent)      // chunk 2 — 1.0 s elapsed
        orchestrator.processChunk(nonSilent)   // chunk 3 — 1.5 s elapsed; switch recorded at 1500 ms
        orchestrator.processChunk(nonSilent)   // chunk 4 — spy reads timestamp 1500 ms

        // No switch during chunks 1–3; 1500 ms on chunk 4
        assertEquals(-1L, capturedTimestamps[0])
        assertEquals(-1L, capturedTimestamps[1])
        assertEquals(-1L, capturedTimestamps[2])
        assertEquals(1500L, capturedTimestamps[3], "Switch timestamp must equal audio elapsed ms (1500 ms)")

        // Verify the timestamp is not a wall-clock value (would be many orders of magnitude larger)
        assertFalse(capturedTimestamps[3] > 1_000_000_000L, "Timestamp must not be a wall-clock epoch ms")
    }

    // ADR-02: globalConfidence must never decrease between consecutive chunks.
    @Test
    fun `globalConfidence is monotonically non-decreasing across chunks`() = runTest(dispatcher) {
        var call = 0
        // Drops from 0.9 to 0.1 between chunk 3 and 4 — orchestrator must not let gauge fall
        val droppingRule = object : AudioDetectionRule {
            override val name = "Dropping"
            override val weight = 1.0f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext) =
                if (call++ < 3) RuleResult(0.8f, 0.9f) else RuleResult(0.2f, 0.1f)
        }
        val orchestrator = DetectionOrchestrator(listOf(droppingRule), dispatcher)

        val confidences = mutableListOf<Float>()
        repeat(5) {
            orchestrator.processChunk(HALF_SECOND_CHUNK)
            confidences += orchestrator.state.value.globalConfidence
        }

        for (i in 1 until confidences.size) {
            assertTrue(
                confidences[i] >= confidences[i - 1],
                "globalConfidence decreased at index $i: ${confidences[i - 1]} → ${confidences[i]}"
            )
        }
    }
}

