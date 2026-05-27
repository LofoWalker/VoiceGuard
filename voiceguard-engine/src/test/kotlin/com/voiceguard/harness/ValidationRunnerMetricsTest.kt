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
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

@DisplayName("ValidationRunner — Story 3.2: per-file verdicts and aggregate metrics")
class ValidationRunnerMetricsTest {

    @Test
    @DisplayName("should compute 100% accuracy and 0% FPR when all human files are correctly classified")
    fun should_compute_correct_accuracy_for_mixed_dataset(@TempDir tempDir: File) = runTest {
        // 4 HUMAN files; engine always returns low aiProbability → HUMAN verdict for all
        val realDir = File(tempDir, "real").apply { mkdirs() }
        repeat(4) { i -> createSyntheticWav(realDir, "human_$i.wav") }

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = {
                FakeChunkProcessor(DetectionUiState(0.8f, 0.1f, 1.0f))
            }
        )

        val summary = runner.runValidation()

        assertEquals(4, summary.totalFiles)
        assertEquals(1.0f, summary.accuracy, "All 4 human files correctly classified → 100% accuracy")
        assertEquals(0.0f, summary.falsePositiveRate, "No human files misclassified as AI → FPR 0%")
    }

    @Test
    @DisplayName("should compute 50% accuracy when engine always returns HUMAN for mixed dataset")
    fun should_compute_50_percent_accuracy_for_mixed_constant_verdict(@TempDir tempDir: File) = runTest {
        // 2 HUMAN + 2 AI files; engine always says HUMAN → 2 correct, 2 wrong → 50% accuracy
        val realDir = File(tempDir, "real").apply { mkdirs() }
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        repeat(2) { i -> createSyntheticWav(realDir, "human_$i.wav") }
        repeat(2) { i -> createSyntheticWav(fakeDir, "ai_$i.wav") }

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = {
                FakeChunkProcessor(DetectionUiState(0.8f, 0.1f, 1.0f)) // always HUMAN verdict
            }
        )

        val summary = runner.runValidation()

        assertEquals(4, summary.totalFiles)
        assertEquals(0.5f, summary.accuracy, absoluteTolerance = 0.001f, "2 correct out of 4 → 50%")
        assertEquals(0.0f, summary.falsePositiveRate, "No human files misclassified as AI → FPR 0%")
    }

    @Test
    @DisplayName("should compute correct false positive rate when human files are misclassified")
    fun should_compute_correct_false_positive_rate(@TempDir tempDir: File) = runTest {
        // 4 HUMAN files; engine misclassifies 1 as AI → FPR = 1/4 = 25%
        val realDir = File(tempDir, "real").apply { mkdirs() }
        repeat(4) { i -> createSyntheticWav(realDir, "human_$i.wav") }

        var fileIndex = 0
        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = {
                // First file gets misclassified as AI; rest are correctly classified as HUMAN
                if (fileIndex++ == 0)
                    FakeChunkProcessor(DetectionUiState(0.8f, 0.9f, 1.0f))  // AI verdict
                else
                    FakeChunkProcessor(DetectionUiState(0.8f, 0.2f, 1.0f))  // HUMAN verdict
            }
        )

        val summary = runner.runValidation()

        assertEquals(1, summary.falsePositives)
        assertEquals(0.25f, summary.falsePositiveRate, absoluteTolerance = 0.001f)
    }

    @Test
    @DisplayName("should exclude low-confidence verdicts from accuracy and FPR")
    fun should_exclude_low_confidence_verdicts(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        createSyntheticWav(realDir, "human.wav")
        createSyntheticWav(fakeDir, "ai.wav")

        // Both files return confidence below the minConfidence gate → should not count
        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.2f, 0.8f, 0.5f)) }
        )

        val summary = runner.runValidation()

        assertEquals(2, summary.totalFiles)
        assertEquals(0, summary.countableVerdicts, "Low-confidence verdicts must be excluded")
    }

    @Test
    @DisplayName("should identify misclassified files in the verdict list")
    fun should_identify_misclassified_files_in_verdict_list(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "misclassified.wav")

        // High confidence but engine thinks it's AI — a false positive
        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.9f, 0.8f, 1.0f)) }
        )

        val summary = runner.runValidation()
        val misclassified = summary.verdicts.filter { !it.isCorrect }

        assertFalse(misclassified.isEmpty(), "Expected at least one misclassified file")
        assertTrue(
            misclassified.any { it.filePath.contains("misclassified.wav") },
            "The specific misclassified file should appear in results"
        )
    }

    @Test
    @DisplayName("should record ground truth label HUMAN for files under the real directory")
    fun should_record_correct_ground_truth_for_human_files(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "human.wav")

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.8f, 0.1f, 1.0f)) }
        )

        val summary = runner.runValidation()

        assertEquals(GroundTruthLabel.HUMAN, summary.verdicts.first().groundTruth)
    }

    @Test
    @DisplayName("should record ground truth label AI for files under the fake directory")
    fun should_record_correct_ground_truth_for_ai_files(@TempDir tempDir: File) = runTest {
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        createSyntheticWav(fakeDir, "ai.wav")

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.8f, 0.9f, 1.0f)) }
        )

        val summary = runner.runValidation()

        assertEquals(GroundTruthLabel.AI, summary.verdicts.first().groundTruth)
    }

    // ── A2 : recall + confusion matrix ─────────────────────────────────────────────

    @Test
    @DisplayName("should compute 100% recall when all AI files are correctly classified")
    fun should_compute_100_percent_recall_when_all_ai_detected(@TempDir tempDir: File) = runTest {
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        repeat(3) { i -> createSyntheticWav(fakeDir, "ai_$i.wav") }

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.8f, 0.9f, 1.0f)) } // AI verdict
        )
        val summary = runner.runValidation()

        assertTrue(summary.metricsAvailable)
        assertEquals(3, summary.totalAiFiles)
        assertEquals(3, summary.truePositives)
        assertEquals(0, summary.falseNegatives)
        assertEquals(1.0f, summary.recall, absoluteTolerance = 0.001f, "All AI detected → recall 100%")
    }

    @Test
    @DisplayName("should compute 0% recall when all AI files are missed")
    fun should_compute_0_percent_recall_when_no_ai_detected(@TempDir tempDir: File) = runTest {
        val fakeDir = File(tempDir, "fake").apply { mkdirs() }
        repeat(3) { i -> createSyntheticWav(fakeDir, "ai_$i.wav") }

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.8f, 0.1f, 1.0f)) } // HUMAN verdict
        )
        val summary = runner.runValidation()

        assertTrue(summary.metricsAvailable)
        assertEquals(0, summary.truePositives)
        assertEquals(3, summary.falseNegatives)
        assertEquals(0.0f, summary.recall, absoluteTolerance = 0.001f, "No AI detected → recall 0%")
    }

    @Test
    @DisplayName("recall is NaN when there are no AI files in the countable set")
    fun recall_is_NaN_when_no_ai_files(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "human.wav")

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.8f, 0.1f, 1.0f)) }
        )
        val summary = runner.runValidation()

        assertTrue(summary.metricsAvailable)
        assertTrue(summary.recall.isNaN(), "No AI files → recall must be NaN, not a fake value")
    }

    // ── A3 : pas de faux-vert metricsAvailable ──────────────────────────────────

    @Test
    @DisplayName("metricsAvailable is false when all verdicts are low-confidence")
    fun metrics_unavailable_when_all_verdicts_low_confidence(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        createSyntheticWav(realDir, "human.wav")

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = { FakeChunkProcessor(DetectionUiState(0.2f, 0.9f, 0.5f)) } // below minConfidence
        )
        val summary = runner.runValidation()

        assertFalse(summary.metricsAvailable, "Low-confidence verdicts must yield metricsAvailable=false")
        assertTrue(summary.accuracy.isNaN(), "accuracy must be NaN — not a misleading 1.0")
        assertTrue(summary.falsePositiveRate.isNaN(), "FPR must be NaN")
        assertTrue(summary.recall.isNaN(), "recall must be NaN")
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

