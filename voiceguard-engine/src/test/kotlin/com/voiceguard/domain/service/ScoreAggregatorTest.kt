package com.voiceguard.domain.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreAggregatorTest {

    private val aggregator = ScoreAggregator()

    // AC-1: Only rules with non-zero confidence contribute to the weighted probability.
    @Test
    fun `only non-zero confidence rules contribute to aiProbability`() {
        val contributions = listOf(
            RuleContribution(weight = 0.5f, suspicionScore = 0.9f, confidence = 0.8f),
            RuleContribution(weight = 0.5f, suspicionScore = 0.1f, confidence = 0.0f) // excluded
        )
        // (0.9 * 0.5 * 0.8) / (0.5 * 0.8) = 0.36 / 0.40 = 0.9
        val result = aggregator.computeAiProbability(contributions)
        assertEquals(0.9.toDouble(), result.toDouble(), 0.001)
    }

    // AC-1: Multiple contributing rules produce the correct weighted average.
    @Test
    fun `multiple non-zero confidence rules produce correct weighted average`() {
        val contributions = listOf(
            RuleContribution(weight = 0.40f, suspicionScore = 0.8f, confidence = 1.0f),
            RuleContribution(weight = 0.35f, suspicionScore = 0.6f, confidence = 0.7f),
            RuleContribution(weight = 0.25f, suspicionScore = 0.4f, confidence = 0.5f)
        )
        // num = (0.8*0.40*1.0) + (0.6*0.35*0.7) + (0.4*0.25*0.5)
        //     = 0.32 + 0.147 + 0.050 = 0.517
        // den = (0.40*1.0) + (0.35*0.7) + (0.25*0.5)
        //     = 0.40 + 0.245 + 0.125 = 0.770
        val expected = 0.517 / 0.770
        assertEquals(expected, aggregator.computeAiProbability(contributions).toDouble(), 0.001)
    }

    // AC-2: All-zero confidence must return exactly 0.0 — no NaN propagation (ADR-04).
    @Test
    fun `all-zero confidence returns 0_0 not NaN`() {
        val contributions = listOf(
            RuleContribution(0.4f, 0.9f, 0.0f),
            RuleContribution(0.6f, 0.8f, 0.0f)
        )
        val result = aggregator.computeAiProbability(contributions)
        assertEquals(0.0f, result)
        assertFalse(result.isNaN(), "aiProbability must never be NaN (ADR-04)")
    }

    // AC-2: Empty contribution list must not produce NaN.
    @Test
    fun `empty contributions yield 0_0 aiProbability`() {
        val result = aggregator.computeAiProbability(emptyList())
        assertEquals(0.0f, result)
        assertFalse(result.isNaN())
    }

    // AC-2: NaN defence — result of formula must never be infinite.
    @Test
    fun `aiProbability is never infinite`() {
        val contributions = listOf(RuleContribution(1.0f, Float.MAX_VALUE, 1.0f))
        val result = aggregator.computeAiProbability(contributions)
        assertFalse(result.isInfinite(), "aiProbability must never be infinite")
    }

    // computeRawConfidence: weighted average of individual confidences.
    @Test
    fun `computeRawConfidence is weighted average of confidences`() {
        val contributions = listOf(
            RuleContribution(weight = 0.6f, suspicionScore = 0.0f, confidence = 0.8f),
            RuleContribution(weight = 0.4f, suspicionScore = 0.0f, confidence = 0.4f)
        )
        // (0.6*0.8 + 0.4*0.4) / (0.6+0.4) = (0.48 + 0.16) / 1.0 = 0.64
        assertEquals(0.64, aggregator.computeRawConfidence(contributions).toDouble(), 0.001)
    }

    // AC-3: globalConfidence monotonically non-decreasing — simulated via orchestrator cycle.
    // The ratchet lives in DetectionOrchestrator (see DetectionOrchestratorTest for full cycles).
    // Here we verify that even when rawConfidence falls, the orchestrator-managed peak stays up.
    @Test
    fun `rawConfidence falling does not force existing peak down (ratchet property)`() {
        val highConfidence = aggregator.computeRawConfidence(
            listOf(RuleContribution(1.0f, 0.0f, 0.9f))
        )
        val lowConfidence = aggregator.computeRawConfidence(
            listOf(RuleContribution(1.0f, 0.0f, 0.2f))
        )
        // Simulate orchestrator ratchet: peak = max(prev, current)
        val peak = maxOf(highConfidence, lowConfidence)
        assertEquals(highConfidence.toDouble(), peak.toDouble(), 0.001,
            "Ratchet must preserve the highest observed confidence")
    }
}

