package com.voiceguard.domain.service

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import com.voiceguard.domain.port.AudioDetectionRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun chunkOf(durationSeconds: Float, sampleRate: Int = 2): AudioChunk {
    val samples = (durationSeconds * sampleRate).toInt().coerceAtLeast(1)
    return AudioChunk(FloatArray(samples) { 0.1f }, sampleRate)
}

private val HALF_SECOND_CHUNK = chunkOf(0.5f)

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionOrchestratorParallelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // AC-1 + AC-2: UnconfinedTestDispatcher can replace the production dispatcher without error.
    @Test
    fun `UnconfinedTestDispatcher substitution works and collects all rule results`() = runTest(dispatcher) {
        var callCount = 0
        val countingRule = object : AudioDetectionRule {
            override val name = "Counter"
            override val weight = 1.0f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                callCount++
                return RuleResult(0.5f, 0.5f)
            }
        }
        val orchestrator = DetectionOrchestrator(listOf(countingRule), dispatcher)

        orchestrator.processChunk(HALF_SECOND_CHUNK)

        assertEquals(1, callCount, "Rule must be called exactly once per processChunk invocation")
    }

    // AC-1: All rules in a multi-rule orchestrator are collected before state is emitted.
    @Test
    fun `all rule results are collected before DetectionUiState is emitted`() = runTest(dispatcher) {
        val results = mutableListOf<String>()
        fun rule(id: String) = object : AudioDetectionRule {
            override val name = id
            override val weight = 0.33f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                results += id
                return RuleResult(0.5f, 0.5f)
            }
        }
        val orchestrator = DetectionOrchestrator(
            listOf(rule("R1"), rule("R2"), rule("R3")),
            dispatcher
        )

        orchestrator.processChunk(HALF_SECOND_CHUNK)

        assertEquals(3, results.size, "All three rules must have been called before state emission")
        assertTrue(results.containsAll(listOf("R1", "R2", "R3")))
    }

    // AC-3: Rules observe the pre-chunk callDurationMillis; mutation happens after awaitAll.
    @Test
    fun `rules see pre-chunk callDurationMillis — context mutated only after awaitAll`() = runTest(dispatcher) {
        var durationObservedByRule = -1L

        val observingRule = object : AudioDetectionRule {
            override val name = "Observer"
            override val weight = 1.0f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                durationObservedByRule = context.callDurationMillis
                return RuleResult(0.5f, 0.5f)
            }
        }
        val orchestrator = DetectionOrchestrator(listOf(observingRule), dispatcher)

        // Initial context state: callDurationMillis == 0
        orchestrator.processChunk(HALF_SECOND_CHUNK)

        assertEquals(0L, durationObservedByRule,
            "Rule must see callDurationMillis = 0 (pre-chunk state); context updated only after awaitAll")
    }

    // AC-3: After processChunk completes, context reflects the updated call duration.
    @Test
    fun `context callDurationMillis is updated after processChunk completes`() = runTest(dispatcher) {
        val rule = object : AudioDetectionRule {
            override val name = "Dummy"
            override val weight = 1.0f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext) = RuleResult(0.5f, 0.5f)
        }
        val orchestrator = DetectionOrchestrator(listOf(rule), dispatcher)

        orchestrator.processChunk(HALF_SECOND_CHUNK)

        // Chunk is 0.5 s at sampleRate=2 → 500 ms
        assertEquals(500L, getContextCallDuration(orchestrator),
            "callDurationMillis must equal 500 after one 0.5-second chunk")
    }

    // AC-3: Multiple chunks accumulate duration correctly via sequential context updates.
    @Test
    fun `callDurationMillis accumulates correctly across multiple chunks`() = runTest(dispatcher) {
        val rule = object : AudioDetectionRule {
            override val name = "Dummy"
            override val weight = 1.0f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext) = RuleResult(0.5f, 0.5f)
        }
        val orchestrator = DetectionOrchestrator(listOf(rule), dispatcher)

        repeat(4) { orchestrator.processChunk(HALF_SECOND_CHUNK) }

        // 4 × 500 ms = 2000 ms
        assertEquals(2000L, getContextCallDuration(orchestrator))
    }

    // AC-3: Context is never mutated while rules are running (no partial-write visibility).
    // Verifies that two rules concurrently reading callDurationMillis see the same pre-chunk value.
    @Test
    fun `concurrent rules observe identical callDurationMillis snapshot`() = runTest(dispatcher) {
        val observed = mutableListOf<Long>()

        fun capturingRule(id: String) = object : AudioDetectionRule {
            override val name = id
            override val weight = 0.5f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult {
                synchronized(observed) { observed += context.callDurationMillis }
                return RuleResult(0.5f, 0.5f)
            }
        }
        val orchestrator = DetectionOrchestrator(
            listOf(capturingRule("A"), capturingRule("B")),
            dispatcher
        )

        orchestrator.processChunk(HALF_SECOND_CHUNK)

        assertEquals(2, observed.size)
        assertTrue(observed.all { it == observed[0] },
            "Both rules must observe the same callDurationMillis — no mid-analysis mutation")
    }
}

/**
 * Reflective accessor to read [ConversationContext.callDurationMillis] from an orchestrator
 * under test without exposing context as public API.
 */
private fun getContextCallDuration(orchestrator: DetectionOrchestrator): Long {
    val contextField = DetectionOrchestrator::class.java.getDeclaredField("context")
    contextField.isAccessible = true
    val ctx = contextField.get(orchestrator) as ConversationContext
    return ctx.callDurationMillis
}

