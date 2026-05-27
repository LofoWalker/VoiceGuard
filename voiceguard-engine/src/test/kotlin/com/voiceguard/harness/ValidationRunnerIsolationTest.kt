package com.voiceguard.harness

import com.voiceguard.domain.port.SpectralClassifierPort
import com.voiceguard.domain.service.DetectionOrchestrator
import com.voiceguard.rules.LatencyBehaviorRule
import com.voiceguard.rules.NoiseLinearityRule
import com.voiceguard.rules.SpectralArtifactsRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ValidationRunner — isolation d'état entre fichiers (C-3)")
class ValidationRunnerIsolationTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /**
     * Regression test for C-3: rules must be fresh per file.
     *
     * Two identical silent WAV files (2 chunks each) are evaluated with real rule instances.
     * Fresh instances per file → each file's confidence ramp starts at 0 → same final globalConfidence.
     * Shared instances (the old bug) → file 2 has accumulated chunkCount → higher confidence → different verdict.
     *
     * The test asserts both files yield the *same* globalConfidence; the absolute value depends on
     * the rule ramp constants and is not pinned here.
     */
    @Test
    fun `identical files produce identical verdicts when rule instances are fresh per file`(
        @TempDir tempDir: File
    ) = runTest {
        val realDir = File(tempDir, "real").apply { mkdirs() }
        // 2 silent files, each 2 chunks (16 000 samples at 16 kHz = 1 s of audio)
        createSyntheticWav(realDir, "file_1.wav", sampleCount = 16_000)
        createSyntheticWav(realDir, "file_2.wav", sampleCount = 16_000)

        val runner = ValidationRunner(
            datasetDir = tempDir,
            processorFactory = {
                // Fresh instances per call: NoiseLinearityRule + LatencyBehaviorRule + SpectralArtifactsRule
                DetectionOrchestratorAdapter(
                    DetectionOrchestrator(
                        rules = listOf(
                            NoiseLinearityRule(),
                            LatencyBehaviorRule(),
                            SpectralArtifactsRule(SpectralClassifierPort { 0.5f })
                        ),
                        dispatcher = dispatcher
                    )
                )
            }
        )

        val summary = runner.runValidation()

        assertEquals(2, summary.totalFiles)
        val confidences = summary.verdicts.map { it.globalConfidence }
        assertEquals(
            confidences[0], confidences[1], absoluteTolerance = 0.001f,
            "Both files must produce the same globalConfidence — no state leakage across files"
        )
    }

    private fun createSyntheticWav(dir: File, name: String, sampleCount: Int) {
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
