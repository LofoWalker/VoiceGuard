package com.voiceguard.adapters

import com.voiceguard.domain.model.AudioChunk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("TFLiteSpectralAdapter")
class TFLiteSpectralAdapterTest {

    private val chunk = AudioChunk(FloatArray(800) { 0.1f })

    // AC-2: when hardware acceleration is unavailable, the adapter must surface it — not swallow it.
    @Test
    fun `should_throw_when_hardware_acceleration_is_unavailable`() {
        assertThrows<IllegalStateException> {
            TFLiteSpectralAdapter(hardwareAccelerationAvailable = false)
        }
    }

    // AC-2: error message must be informative — not a silent generic failure.
    @Test
    fun `error_message_must_describe_missing_hardware_acceleration`() {
        val ex = runCatching { TFLiteSpectralAdapter(hardwareAccelerationAvailable = false) }
            .exceptionOrNull() as? IllegalStateException
        assertNotNull(ex, "Expected IllegalStateException")
        assertTrue(
            ex.message?.contains("hardware acceleration", ignoreCase = true) == true,
            "Error message must mention hardware acceleration"
        )
    }

    // AC-1: when hardware acceleration is available, adapter initializes without error.
    @Test
    fun `should_initialize_successfully_when_hardware_acceleration_is_available`() {
        assertDoesNotThrow {
            TFLiteSpectralAdapter(hardwareAccelerationAvailable = true)
        }
    }

    // AC-3: domain contract (SpectralClassifierPort) is satisfied — adapter exposes classify().
    @Test
    fun `should_return_a_valid_score_in_range_when_hardware_is_available`() {
        val adapter = TFLiteSpectralAdapter(hardwareAccelerationAvailable = true)
        val score = adapter.classify(chunk)
        assertTrue(score in 0f..1f, "classify() must return a score in [0.0, 1.0]: $score")
    }

    // AC-2: default construction in a JVM environment (no NNAPI) must fail rather than silently proceed.
    @Test
    fun `should_throw_by_default_in_jvm_environment_where_nnapi_is_absent`() {
        assertThrows<IllegalStateException> {
            // No explicit flag → uses detectHardwareAcceleration() which returns false in JVM.
            TFLiteSpectralAdapter()
        }
    }
}

