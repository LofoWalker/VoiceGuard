package com.voiceguard.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RuleResultTest {

    @Test
    fun `confidence zero signals no evidence yet`() {
        val result = RuleResult(suspicionScore = 0.5f, confidence = 0.0f)
        assertEquals(0.0f, result.confidence)
    }

    @Test
    fun `two results with same values are equal`() {
        val a = RuleResult(0.7f, 0.9f)
        val b = RuleResult(0.7f, 0.9f)
        assertEquals(a, b)
    }

    @Test
    fun `two results with different suspicion scores are not equal`() {
        assertNotEquals(RuleResult(0.3f, 0.5f), RuleResult(0.4f, 0.5f))
    }
}

