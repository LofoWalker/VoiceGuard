package com.voiceguard.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectionUiStateTest {

    @Test
    fun `globalConfidence is in range 0 to 1`() {
        val state = DetectionUiState(globalConfidence = 0.5f, aiProbability = 0.3f, elapsedSeconds = 2.0f)
        assertTrue(state.globalConfidence in 0.0f..1.0f)
    }

    @Test
    fun `two states with same values are equal`() {
        val a = DetectionUiState(0.5f, 0.3f, 2.0f)
        val b = DetectionUiState(0.5f, 0.3f, 2.0f)
        assertEquals(a, b)
    }
}

