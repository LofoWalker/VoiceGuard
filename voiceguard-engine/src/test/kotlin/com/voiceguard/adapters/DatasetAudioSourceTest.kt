package com.voiceguard.adapters

import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("DatasetAudioSource")
class DatasetAudioSourceTest {

    @Test
    @DisplayName("should produce correct number of AudioChunk objects from a known WAV file")
    fun should_produce_correct_chunk_count_from_wav_file(@TempDir tempDir: File) = runTest {
        val wavFile = createSyntheticWav(tempDir, sampleCount = 16_000)

        val chunks = DatasetAudioSource(wavFile).audioStream().toList()

        assertEquals(2, chunks.size)
    }

    @Test
    @DisplayName("should set sampleRate to 16000 on every produced AudioChunk")
    fun should_set_sample_rate_16000_on_every_chunk(@TempDir tempDir: File) = runTest {
        val wavFile = createSyntheticWav(tempDir, sampleCount = 16_000)

        val chunks = DatasetAudioSource(wavFile).audioStream().toList()

        assertTrue(chunks.all { it.sampleRate == 16_000 })
    }

    @Test
    @DisplayName("should produce exactly 8000 samples per chunk for 500 ms at 16 kHz")
    fun should_produce_8000_samples_per_chunk(@TempDir tempDir: File) = runTest {
        val wavFile = createSyntheticWav(tempDir, sampleCount = 16_000)

        val chunks = DatasetAudioSource(wavFile).audioStream().toList()

        chunks.forEach { assertEquals(8_000, it.pcmData.size) }
    }

    @Test
    @DisplayName("should produce identical AudioChunk sequences on repeated replay of the same file")
    fun should_produce_identical_sequences_on_repeated_replay(@TempDir tempDir: File) = runTest {
        val wavFile = createSyntheticWav(tempDir, sampleCount = 16_000)
        val source = DatasetAudioSource(wavFile)

        val firstReplay: List<AudioChunk> = source.audioStream().toList()
        val secondReplay: List<AudioChunk> = source.audioStream().toList()

        assertEquals(firstReplay.size, secondReplay.size)
        firstReplay.zip(secondReplay).forEach { (a, b) ->
            assertEquals(a, b, "Chunks must be identical across replays for deterministic evaluation")
        }
    }

    @Test
    @DisplayName("should include a partial final chunk when audio length is not a multiple of 500 ms")
    fun should_include_partial_tail_chunk(@TempDir tempDir: File) = runTest {
        // 16000 + 4000 = 1.25 s → 2 full chunks + 1 partial (4000 samples)
        val wavFile = createSyntheticWav(tempDir, sampleCount = 20_000)

        val chunks = DatasetAudioSource(wavFile).audioStream().toList()

        assertEquals(3, chunks.size)
        assertEquals(4_000, chunks.last().pcmData.size)
    }

    @Test
    @DisplayName("should downmix stereo WAV to mono by keeping the left channel only")
    fun should_downmix_stereo_wav_to_mono(@TempDir tempDir: File) = runTest {
        // Stereo WAV: left channel = value 1000, right channel = value -1000
        // After mono reduction, all samples must reflect the left channel (positive value).
        val stereoFile = createStereoWav(
            dir = tempDir,
            sampleCount = 8_000,   // 8000 frames × 2 channels
            leftValue = 1_000,
            rightValue = -1_000
        )

        val chunks = DatasetAudioSource(stereoFile).audioStream().toList()

        assertEquals(1, chunks.size, "8000 mono samples = 1 chunk of 500 ms")
        assertTrue(
            chunks.first().pcmData.all { it > 0f },
            "All samples must be positive (left channel was +1000, right was -1000)"
        )
    }

    @Test
    @DisplayName("should produce the same chunk count from a stereo WAV as from its mono equivalent")
    fun should_produce_same_chunk_count_from_stereo_as_mono(@TempDir tempDir: File) = runTest {
        val monoFile = createSyntheticWav(tempDir, sampleCount = 16_000, name = "mono.wav")
        val stereoFile = createStereoWav(tempDir, sampleCount = 16_000, name = "stereo.wav")

        val monoChunks = DatasetAudioSource(monoFile).audioStream().toList()
        val stereoChunks = DatasetAudioSource(stereoFile).audioStream().toList()

        assertEquals(monoChunks.size, stereoChunks.size)
    }

    // -------------------------------------------------------------------------
    // WAV file helpers
    // -------------------------------------------------------------------------

    private fun createSyntheticWav(
        dir: File,
        sampleCount: Int,
        name: String = "test.wav",
        sampleRate: Int = 16_000
    ): File {
        val file = File(dir, name)
        val bitsPerSample = 16
        val numChannels = 1
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataSize = sampleCount * blockAlign

        DataOutputStream(FileOutputStream(file)).use { out ->
            writeWavHeader(out, dataSize, sampleRate, numChannels, bitsPerSample)
            repeat(sampleCount) { writeLEShort(out, 0) }
        }
        return file
    }

    /**
     * Writes a stereo 16-bit WAV where [leftValue] fills the left channel and
     * [rightValue] fills the right channel across [sampleCount] frames.
     */
    private fun createStereoWav(
        dir: File,
        sampleCount: Int,
        leftValue: Int = 0,
        rightValue: Int = 0,
        name: String = "stereo.wav",
        sampleRate: Int = 16_000
    ): File {
        val file = File(dir, name)
        val bitsPerSample = 16
        val numChannels = 2
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataSize = sampleCount * blockAlign

        DataOutputStream(FileOutputStream(file)).use { out ->
            writeWavHeader(out, dataSize, sampleRate, numChannels, bitsPerSample)
            repeat(sampleCount) {
                writeLEShort(out, leftValue)   // left channel
                writeLEShort(out, rightValue)  // right channel
            }
        }
        return file
    }

    private fun writeWavHeader(
        out: DataOutputStream,
        dataSize: Int,
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val blockAlign = numChannels * (bitsPerSample / 8)
        out.writeBytes("RIFF"); writeLEInt(out, 36 + dataSize); out.writeBytes("WAVE")
        out.writeBytes("fmt "); writeLEInt(out, 16); writeLEShort(out, 1)
        writeLEShort(out, numChannels); writeLEInt(out, sampleRate)
        writeLEInt(out, byteRate); writeLEShort(out, blockAlign); writeLEShort(out, bitsPerSample)
        out.writeBytes("data"); writeLEInt(out, dataSize)
    }

    private fun writeLEInt(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }

    private fun writeLEShort(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }
}

