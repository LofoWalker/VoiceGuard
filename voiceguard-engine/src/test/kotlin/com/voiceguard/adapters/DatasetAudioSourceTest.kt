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
        // 16000 samples @ 16 kHz = 1 second → 2 chunks of 8000 samples (500 ms each)
        val wavFile = createSyntheticWav(tempDir, sampleCount = 16_000)

        val source = DatasetAudioSource(wavFile)
        val chunks = source.audioStream().toList()

        assertEquals(2, chunks.size)
    }

    @Test
    @DisplayName("should set sampleRate to 16000 on every produced AudioChunk")
    fun should_set_sample_rate_16000_on_every_chunk(@TempDir tempDir: File) = runTest {
        val wavFile = createSyntheticWav(tempDir, sampleCount = 16_000)

        val source = DatasetAudioSource(wavFile)
        val chunks = source.audioStream().toList()

        assertTrue(chunks.all { it.sampleRate == 16_000 })
    }

    @Test
    @DisplayName("should produce exactly 8000 samples per chunk for 500 ms at 16 kHz")
    fun should_produce_8000_samples_per_chunk(@TempDir tempDir: File) = runTest {
        val wavFile = createSyntheticWav(tempDir, sampleCount = 16_000)

        val source = DatasetAudioSource(wavFile)
        val chunks = source.audioStream().toList()

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
        // 16000 + 4000 samples = 1.25 seconds → 2 full chunks + 1 partial chunk of 4000 samples
        val wavFile = createSyntheticWav(tempDir, sampleCount = 20_000)

        val source = DatasetAudioSource(wavFile)
        val chunks = source.audioStream().toList()

        assertEquals(3, chunks.size)
        assertEquals(4_000, chunks.last().pcmData.size)
    }

    /** Writes a minimal valid 16-bit PCM WAV file containing [sampleCount] silence samples at 16 kHz. */
    private fun createSyntheticWav(dir: File, sampleCount: Int): File {
        val file = File(dir, "test.wav")
        val sampleRate = 16_000
        val bitsPerSample = 16
        val numChannels = 1
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataSize = sampleCount * blockAlign

        DataOutputStream(FileOutputStream(file)).use { out ->
            // RIFF header
            out.writeBytes("RIFF")
            writeLittleEndianInt(out, 36 + dataSize)
            out.writeBytes("WAVE")
            // fmt chunk
            out.writeBytes("fmt ")
            writeLittleEndianInt(out, 16)
            writeLittleEndianShort(out, 1)            // PCM
            writeLittleEndianShort(out, numChannels)
            writeLittleEndianInt(out, sampleRate)
            writeLittleEndianInt(out, byteRate)
            writeLittleEndianShort(out, blockAlign)
            writeLittleEndianShort(out, bitsPerSample)
            // data chunk
            out.writeBytes("data")
            writeLittleEndianInt(out, dataSize)
            // silence samples
            repeat(sampleCount) { writeLittleEndianShort(out, 0) }
        }
        return file
    }

    private fun writeLittleEndianInt(out: DataOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeLittleEndianShort(out: DataOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}

