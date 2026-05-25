package com.voiceguard.harness

import com.voiceguard.domain.model.DetectionUiState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("ValidationRunner — Story 3.4: Gradle entry point smoke tests")
class ValidationRunnerSmokeTest {

    @Test
    @DisplayName("should complete without error on a minimal test dataset")
    fun should_complete_without_error_on_minimal_dataset(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        createSyntheticWav(realDir, "human.wav")
        createSyntheticWav(fakeDir, "ai.wav")

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.7f, 0.4f, 1.0f)) }
        )

        val summary = runner.runValidation()

        assertNotNull(summary, "runValidation() must return a non-null summary")
        assertEquals(2, summary.totalFiles)
    }

    @Test
    @DisplayName("should produce a summary with non-negative metric values on an empty dataset")
    fun should_produce_valid_summary_on_empty_dataset(@TempDir tempDir: File) = runTest {
        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.0f, 0.0f, 0.0f)) }
        )

        val summary = runner.runValidation()

        assertEquals(0, summary.totalFiles)
        assertFalse(summary.metricsAvailable, "No countable verdicts → metricsAvailable must be false")
        assertTrue(summary.accuracy.isNaN(), "No countable verdicts → accuracy must be NaN, not a fake 1.0")
        assertTrue(summary.falsePositiveRate.isNaN(), "No countable verdicts → FPR must be NaN")
        assertEquals(0L, summary.budgetViolationCount.toLong())
    }

    @Test
    @DisplayName("printReport should not throw for any valid summary")
    fun should_not_throw_when_printing_report(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "sample.wav")

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.8f, 0.9f, 1.0f)) }
        )

        val summary = runner.runValidation()
        // Must not throw
        summary.printReport()
    }

    private fun createSyntheticWav(dir: File, name: String, sampleCount: Int = 8_000) {
        val file = File(dir, name)
        val sampleRate = 16_000; val bitsPerSample = 16; val numChannels = 1
        val blockAlign = numChannels * (bitsPerSample / 8); val dataSize = sampleCount * blockAlign

        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF"); writeLEInt(out, 36 + dataSize); out.writeBytes("WAVE")
            out.writeBytes("fmt "); writeLEInt(out, 16); writeLEShort(out, 1)
            writeLEShort(out, numChannels); writeLEInt(out, sampleRate)
            writeLEInt(out, sampleRate * blockAlign); writeLEShort(out, blockAlign); writeLEShort(out, bitsPerSample)
            out.writeBytes("data"); writeLEInt(out, dataSize)
            repeat(sampleCount) { writeLEShort(out, 0) }
        }
    }

    private fun writeLEInt(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }

    private fun writeLEShort(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }
}

