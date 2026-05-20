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

@DisplayName("ValidationRunner — Story 3.1: dataset audio replay")
class ValidationRunnerBasicTest {

    @Test
    @DisplayName("should complete without error and produce verdicts for all audio files")
    fun should_complete_without_error_and_produce_verdicts(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        createSyntheticWav(realDir, "human_sample.wav", sampleCount = 16_000)
        createSyntheticWav(fakeDir, "ai_sample.wav", sampleCount = 16_000)

        val fakeState = DetectionUiState(globalConfidence = 0.7f, aiProbability = 0.3f, elapsedSeconds = 1.0f)
        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(finalState = fakeState) }
        )

        val summary = runner.runValidation()

        assertEquals(2, summary.totalFiles)
        assertTrue(summary.verdicts.isNotEmpty())
    }

    @Test
    @DisplayName("should feed chunks from DatasetAudioSource into the processor for each file")
    fun should_feed_chunks_for_each_file(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "sample.wav", sampleCount = 16_000)

        var processedChunks = 0
        val countingProcessor = object : ChunkProcessor {
            override suspend fun processChunk(chunk: com.voiceguard.domain.model.AudioChunk) {
                processedChunks++
            }
            override fun currentState() = DetectionUiState(0.0f, 0.0f, 0.0f)
        }
        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { countingProcessor }
        )

        runner.runValidation()

        assertEquals(2, processedChunks, "Expected 2 chunks for a 1-second WAV at 16 kHz")
    }

    @Test
    @DisplayName("should skip files in directories with unrecognised ground-truth labels")
    fun should_skip_files_with_unknown_ground_truth(@TempDir tempDir: File) = runTest {
        val unknownDir = File(tempDir, "unknown").apply { mkdirs() }
        createSyntheticWav(unknownDir, "ambiguous.wav", sampleCount = 8_000)

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.0f, 0.0f, 0.0f)) }
        )

        val summary = runner.runValidation()

        assertEquals(0, summary.totalFiles, "Files with unknown directory labels must be skipped")
    }

    private fun createSyntheticWav(dir: File, name: String, sampleCount: Int) {
        val file = File(dir, name)
        val sampleRate = 16_000
        val bitsPerSample = 16
        val numChannels = 1
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataSize = sampleCount * blockAlign

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

