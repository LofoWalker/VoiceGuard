package com.voiceguard.harness

import com.voiceguard.domain.model.DetectionUiState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ValidationRunner.computeThresholdSweep] (exercised through [ValidationRunner.runValidation]).
 *
 * Design notes
 * ------------
 * The sweep is computed from per-file aiProbability values in the countable verdicts — no
 * additional pipeline runs are needed. Tests exercise:
 *   - The shape of the sweep curve (19 points, step 0.05).
 *   - Specific accuracy / FPR / recall values at well-chosen thresholds.
 *   - The empty-sweep invariant when only one class is present.
 *
 * File-ordering assumption: [File.walkTopDown] visits directories in [File.listFiles] order,
 * which on a fresh temp directory is typically creation order. AI files (fake/) are therefore
 * created — and processed — before HUMAN files (real/), matching the factory-call sequence.
 * This mirrors the same assumption used in [ValidationRunnerMetricsTest].
 */
@DisplayName("ValidationRunner — computeThresholdSweep unit tests")
class ValidationRunnerThresholdSweepTest {

    // ── Shape ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sweep has exactly 19 points covering thresholds 0.05 to 0.95 in 0.05 steps")
    fun sweep_has_19_points(@TempDir tempDir: File) = runTest {
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(fakeDir, "ai.wav")
        createSyntheticWav(realDir, "human.wav")

        val summary = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(1.0f, 0.8f, 1.0f)) }
        ).runValidation()

        assertEquals(19, summary.thresholdSweep.size, "Sweep must cover steps 1..19 (0.05 to 0.95)")
        assertEquals(
            0.05f, summary.thresholdSweep.first().threshold,
            absoluteTolerance = 0.001f, "First threshold must be 0.05"
        )
        assertEquals(
            0.95f, summary.thresholdSweep.last().threshold,
            absoluteTolerance = 0.001f, "Last threshold must be 0.95"
        )
    }

    // ── Metric correctness at specific thresholds ──────────────────────────────

    @Test
    @DisplayName("at threshold 0.55 both AI (prob 0.8) and HUMAN (prob 0.2) are classified correctly")
    fun sweep_point_at_0_55_is_perfect_when_classes_are_well_separated(@TempDir tempDir: File) = runTest {
        // 2 AI files (aiProbability = 0.8), 2 HUMAN files (aiProbability = 0.2).
        // At threshold 0.55: tp=2, fp=0 → accuracy=1.0, FPR=0.0, recall=1.0.
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        val realDir = File(tempDir, "real").apply { mkdirs() }
        repeat(2) { i -> createSyntheticWav(fakeDir, "ai_$i.wav") }
        repeat(2) { i -> createSyntheticWav(realDir, "human_$i.wav") }

        var callIndex = 0
        val summary = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = {
                // First 2 calls → AI files (fake/), next 2 → HUMAN files (real/).
                if (callIndex++ < 2)
                    FakeChunkProcessor(DetectionUiState(1.0f, 0.8f, 1.0f))
                else
                    FakeChunkProcessor(DetectionUiState(1.0f, 0.2f, 1.0f))
            }
        ).runValidation()

        val at55 = summary.thresholdSweep.find { abs(it.threshold - 0.55f) < 0.001f }!!

        assertEquals(1.0f, at55.accuracy,          absoluteTolerance = 0.001f, "accuracy at 0.55")
        assertEquals(0.0f, at55.falsePositiveRate,  absoluteTolerance = 0.001f, "FPR at 0.55")
        assertEquals(1.0f, at55.recall,             absoluteTolerance = 0.001f, "recall at 0.55")
    }

    @Test
    @DisplayName("at threshold 0.85 AI files (prob 0.8) are all missed — accuracy drops to 0.5, recall to 0.0")
    fun sweep_point_at_0_85_misses_all_ai_when_prob_is_0_8(@TempDir tempDir: File) = runTest {
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        val realDir = File(tempDir, "real").apply { mkdirs() }
        repeat(2) { i -> createSyntheticWav(fakeDir, "ai_$i.wav") }
        repeat(2) { i -> createSyntheticWav(realDir, "human_$i.wav") }

        var callIndex = 0
        val summary = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = {
                if (callIndex++ < 2)
                    FakeChunkProcessor(DetectionUiState(1.0f, 0.8f, 1.0f))
                else
                    FakeChunkProcessor(DetectionUiState(1.0f, 0.2f, 1.0f))
            }
        ).runValidation()

        // At t=0.85: 0.8 < 0.85 → all AI missed, no FP → accuracy = (0+2)/4 = 0.5
        val at85 = summary.thresholdSweep.find { abs(it.threshold - 0.85f) < 0.001f }!!

        assertEquals(0.5f,  at85.accuracy,         absoluteTolerance = 0.001f, "accuracy at 0.85")
        assertEquals(0.0f,  at85.falsePositiveRate, absoluteTolerance = 0.001f, "FPR at 0.85")
        assertEquals(0.0f,  at85.recall,            absoluteTolerance = 0.001f, "recall at 0.85")
    }

    // ── Monotonicity ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("recall and FPR are non-increasing as threshold rises")
    fun recall_and_fpr_are_non_increasing_as_threshold_rises(@TempDir tempDir: File) = runTest {
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        val realDir = File(tempDir, "real").apply { mkdirs() }
        repeat(3) { i -> createSyntheticWav(fakeDir, "ai_$i.wav") }
        repeat(3) { i -> createSyntheticWav(realDir, "human_$i.wav") }

        var callIndex = 0
        val summary = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = {
                if (callIndex++ < 3)
                    FakeChunkProcessor(DetectionUiState(1.0f, 0.8f, 1.0f))
                else
                    FakeChunkProcessor(DetectionUiState(1.0f, 0.3f, 1.0f))
            }
        ).runValidation()

        val sweep = summary.thresholdSweep
        for (i in 1 until sweep.size) {
            assertTrue(
                sweep[i].recall <= sweep[i - 1].recall + 0.001f,
                "recall must be non-increasing: ${sweep[i-1].threshold} → ${sweep[i].threshold}"
            )
            assertTrue(
                sweep[i].falsePositiveRate <= sweep[i - 1].falsePositiveRate + 0.001f,
                "FPR must be non-increasing: ${sweep[i-1].threshold} → ${sweep[i].threshold}"
            )
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sweep is empty when only HUMAN files are present (no AI class)")
    fun sweep_is_empty_when_only_human_files(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "human.wav")

        val summary = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(1.0f, 0.8f, 1.0f)) }
        ).runValidation()

        assertTrue(summary.thresholdSweep.isEmpty(), "Sweep must be empty when the AI class is absent")
    }

    @Test
    @DisplayName("sweep is empty when only AI files are present (no HUMAN class)")
    fun sweep_is_empty_when_only_ai_files(@TempDir tempDir: File) = runTest {
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        createSyntheticWav(fakeDir, "ai.wav")

        val summary = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(1.0f, 0.8f, 1.0f)) }
        ).runValidation()

        assertTrue(summary.thresholdSweep.isEmpty(), "Sweep must be empty when the HUMAN class is absent")
    }

    @Test
    @DisplayName("sweep is empty when the dataset directory is empty")
    fun sweep_is_empty_when_dataset_is_empty(@TempDir tempDir: File) = runTest {
        val summary = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(1.0f, 0.8f, 1.0f)) }
        ).runValidation()

        assertTrue(summary.thresholdSweep.isEmpty(), "Sweep must be empty when no verdicts are available")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createSyntheticWav(dir: File, name: String, sampleCount: Int = 8_000) {
        val file = File(dir, name)
        val sampleRate = 16_000; val bitsPerSample = 16; val numChannels = 1
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataSize = sampleCount * blockAlign

        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF"); writeLEInt(out, 36 + dataSize); out.writeBytes("WAVE")
            out.writeBytes("fmt "); writeLEInt(out, 16); writeLEShort(out, 1)
            writeLEShort(out, numChannels); writeLEInt(out, sampleRate)
            writeLEInt(out, sampleRate * blockAlign); writeLEShort(out, blockAlign)
            writeLEShort(out, bitsPerSample)
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
