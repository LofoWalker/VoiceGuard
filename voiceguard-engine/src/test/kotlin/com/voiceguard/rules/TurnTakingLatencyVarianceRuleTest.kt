package com.voiceguard.rules

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("TurnTakingLatencyVarianceRule (VG-019)")
class TurnTakingLatencyVarianceRuleTest {

    private lateinit var rule: TurnTakingLatencyVarianceRule
    private lateinit var context: ConversationContext
    private val chunk = AudioChunk(FloatArray(800) { 0.1f })

    @BeforeEach
    fun setUp() {
        rule = TurnTakingLatencyVarianceRule()
        context = ConversationContext()
    }

    // AC: no speech switches → confidence = 0 (warm-up exclusion).
    @Test
    fun `abstains with no speech switches`() = runTest {
        val result = rule.analyze(chunk, context)

        assertEquals(0.0f, result.confidence, "Must abstain with no speech switches")
        assertEquals(0.0f, result.suspicionScore, "Suspicion must be 0 with no evidence")
    }

    // AC: regular intervals (CV ≈ 0) → suspicion ≈ 1.
    @Test
    fun `perfectly regular intervals yield high suspicion`() = runTest {
        val base = System.currentTimeMillis()
        // All gaps = 1000 ms → CV = 0 → suspicion = 1
        listOf(0L, 1000L, 2000L, 3000L, 4000L, 5000L).forEach {
            context.recordSpeechSwitch(base + it)
        }

        val result = rule.analyze(chunk, context)

        assertTrue(result.confidence > 0f, "Must have confidence with enough switches")
        assertTrue(result.suspicionScore > 0.7f,
            "Perfectly regular response latency must be suspicious: ${result.suspicionScore}")
    }

    // AC: highly variable intervals (CV >> 0.5) → low suspicion.
    @Test
    fun `highly variable intervals yield low suspicion`() = runTest {
        val base = System.currentTimeMillis()
        // Cumulative offsets for intervals: 100, 900, 80, 1200, 50, 1100 ms → high variance
        val offsets = listOf(0L, 100L, 1000L, 1080L, 2280L, 2330L, 3430L)
        offsets.forEach { context.recordSpeechSwitch(base + it) }

        val result = rule.analyze(chunk, context)

        assertTrue(result.suspicionScore < 0.5f,
            "Highly variable latency must not be suspicious: ${result.suspicionScore}")
    }

    // AC: regular intervals are more suspicious than variable ones.
    @Test
    fun `regular intervals are more suspicious than variable intervals`() = runTest {
        val base = System.currentTimeMillis()

        val regularContext = ConversationContext()
        listOf(0L, 1000L, 2000L, 3000L, 4000L).forEach {
            regularContext.recordSpeechSwitch(base + it)
        }
        val regularSuspicion = TurnTakingLatencyVarianceRule().analyze(chunk, regularContext).suspicionScore

        val variableContext = ConversationContext()
        listOf(0L, 150L, 1050L, 1130L, 2430L).forEach {
            variableContext.recordSpeechSwitch(base + it)
        }
        val variableSuspicion = TurnTakingLatencyVarianceRule().analyze(chunk, variableContext).suspicionScore

        assertTrue(regularSuspicion > variableSuspicion,
            "Regular latency must be more suspicious than variable: regular=$regularSuspicion variable=$variableSuspicion")
    }

    // AC: rule never mutates ConversationContext.
    @Test
    fun `does not mutate ConversationContext`() = runTest {
        val base = System.currentTimeMillis()
        context.recordSpeechSwitch(base)
        context.recordSpeechSwitch(base + 1000L)

        val countBefore = context.speechSwitchTimestamps.size
        rule.analyze(chunk, context)

        assertEquals(countBefore, context.speechSwitchTimestamps.size,
            "Rule must not add speech switches to context")
    }
}
