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
import kotlin.test.assertTrue

@DisplayName("ValidationRunner — Story 3.3: chunk-processing latency measurement")
class ValidationRunnerLatencyTest {

    @Test
    @DisplayName("should record positive latency for each processed chunk")
    fun should_record_positive_latency_per_chunk(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "sample.wav", sampleCount = 16_000) // 2 chunks

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.8f, 0.3f, 1.0f)) }
        )

        val summary = runner.runValidation()

        assertTrue(summary.meanLatencyMs >= 0.0, "Mean latency must be non-negative")
        assertTrue(summary.maxLatencyMs >= 0L, "Max latency must be non-negative")
    }

    @Test
    @DisplayName("should measure latency that reflects actual processing time via System.nanoTime")
    fun should_measure_latency_reflecting_actual_processing_time(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "slow.wav", sampleCount = 8_000) // 1 chunk

        // Force a 30ms delay per chunk to verify the measurement captures it
        val delayMs = 30L
        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = {
                FakeChunkProcessor(
                    finalState = DetectionUiState(0.8f, 0.3f, 0.5f),
                    processingDelayMs = delayMs
                )
            }
        )

        val summary = runner.runValidation()

        assertTrue(
            summary.meanLatencyMs >= delayMs.toDouble(),
            "Measured latency (${summary.meanLatencyMs} ms) must be >= injected delay ($delayMs ms)"
        )
    }

    @Test
    @DisplayName("should count exactly one budget violation when one chunk exceeds 50 ms")
    fun should_count_one_budget_violation_when_one_chunk_exceeds_budget(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }

        // 4 chunks: 3 fast files (1 chunk each) + 1 slow file (1 slow chunk)
        repeat(3) { i -> createSyntheticWav(realDir, "fast_$i.wav", sampleCount = 8_000) }

        val slowDir = File(tempDir, "fake").apply { mkdirs() }
        createSyntheticWav(slowDir, "slow.wav", sampleCount = 8_000)

        var callCount = 0
        val runner = ValidationRunner(
            datasetDir = tempDir,
            config = ValidationConfig(budgetMs = 50L),
            processorFactory = {
                callCount++
                if (callCount <= 3)
                    // Fast processors: < 50 ms (effectively instantaneous)
                    FakeChunkProcessor(DetectionUiState(0.8f, 0.3f, 0.5f), processingDelayMs = 0L)
                else
                    // Slow processor: exceeds 50 ms budget
                    FakeChunkProcessor(DetectionUiState(0.8f, 0.8f, 0.5f), processingDelayMs = 60L)
            }
        )

        val summary = runner.runValidation()

        assertEquals(
            1,
            summary.budgetViolationCount,
            "Exactly 1 chunk should exceed the 50 ms budget"
        )
    }

    @Test
    @DisplayName("should report zero budget violations when all chunks are within the 50 ms budget")
    fun should_report_zero_violations_when_all_chunks_within_budget(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "sample.wav", sampleCount = 16_000)

        val runner = ValidationRunner(
            datasetDir = tempDir,
            config = ValidationConfig(budgetMs = 50L),
            processorFactory = {
                FakeChunkProcessor(
                    finalState = DetectionUiState(0.8f, 0.1f, 1.0f),
                    processingDelayMs = 0L
                )
            }
        )

        val summary = runner.runValidation()

        assertEquals(0, summary.budgetViolationCount)
    }

    private fun createSyntheticWav(dir: File, name: String, sampleCount: Int = 16_000) {
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

