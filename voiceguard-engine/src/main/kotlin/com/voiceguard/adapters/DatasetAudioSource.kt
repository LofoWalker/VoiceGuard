package com.voiceguard.adapters

import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.port.AudioSourcePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * File-based [AudioSourcePort] that reads a WAV or raw PCM file and emits consecutive
 * 500 ms [AudioChunk] windows, bypassing any microphone or OS audio capture.
 *
 * Designed for the Phase 1 validation harness: the same file always produces the same
 * byte-identical chunk sequence, guaranteeing deterministic evaluation runs.
 *
 * Supported format: 16-bit PCM, mono, 16 kHz (standard telephony format).
 * WAV files are detected by the RIFF header; any other file is treated as raw PCM.
 *
 * @param audioFile Source audio file — must exist and be readable.
 */
class DatasetAudioSource(private val audioFile: File) : AudioSourcePort {

    init {
        require(audioFile.exists()) { "Audio file not found: ${audioFile.absolutePath}" }
        require(audioFile.canRead()) { "Audio file not readable: ${audioFile.absolutePath}" }
    }

    override fun audioStream(): Flow<AudioChunk> = flow {
        val (pcmBytes, sampleRate) = readPcmBytes(audioFile)

        // 2 bytes per 16-bit sample; 500 ms window at 16 kHz = 8000 samples = 16000 bytes
        val samplesPerChunk = sampleRate / 2
        val bytesPerSample = 2
        val bytesPerChunk = samplesPerChunk * bytesPerSample

        var offset = 0
        while (offset < pcmBytes.size) {
            val end = minOf(offset + bytesPerChunk, pcmBytes.size)
            val chunkBytes = pcmBytes.copyOfRange(offset, end)
            val samples = chunkBytes.toNormalizedFloats()
            emit(AudioChunk(pcmData = samples, sampleRate = sampleRate))
            offset = end
        }
    }

    /** Returns raw PCM bytes and the detected sample rate, stripping the WAV header if present. */
    private fun readPcmBytes(file: File): Pair<ByteArray, Int> {
        val bytes = file.readBytes()
        if (isWavFile(bytes)) {
            return parseWav(bytes)
        }
        // Raw PCM: assume 16 kHz
        return Pair(bytes, DEFAULT_SAMPLE_RATE)
    }

    private fun isWavFile(bytes: ByteArray): Boolean =
        bytes.size > 12 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()

    /**
     * Parses the WAV container and returns the raw PCM data bytes along with the sample rate.
     * Supports standard 16-bit mono PCM WAV (format tag 1).
     */
    private fun parseWav(bytes: ByteArray): Pair<ByteArray, Int> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Skip RIFF header: "RIFF" (4) + fileSize (4) + "WAVE" (4)
        buffer.position(12)

        var sampleRate = DEFAULT_SAMPLE_RATE
        var dataStart = 0
        var dataSize = 0

        while (buffer.remaining() >= 8) {
            val chunkId = String(ByteArray(4).also { buffer.get(it) })
            val chunkSize = buffer.int

            when (chunkId) {
                "fmt " -> {
                    buffer.short  // audio format (1 = PCM)
                    buffer.short  // num channels
                    sampleRate = buffer.int
                    // Skip byteRate, blockAlign, bitsPerSample
                    val remaining = chunkSize - 8
                    buffer.position(buffer.position() + maxOf(0, remaining))
                }
                "data" -> {
                    dataStart = buffer.position()
                    dataSize = chunkSize
                    break
                }
                else -> buffer.position(buffer.position() + chunkSize)
            }
        }

        require(dataStart > 0) { "No 'data' chunk found in WAV file: ${audioFile.name}" }
        val pcmBytes = bytes.copyOfRange(dataStart, minOf(dataStart + dataSize, bytes.size))
        return Pair(pcmBytes, sampleRate)
    }

    /** Converts 16-bit little-endian PCM bytes to normalized [-1.0, 1.0] floats. */
    private fun ByteArray.toNormalizedFloats(): FloatArray {
        val sampleCount = size / 2
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(sampleCount) { buffer.short / SHORT_MAX_VALUE }
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 16_000
        private const val SHORT_MAX_VALUE = 32768f
    }
}

