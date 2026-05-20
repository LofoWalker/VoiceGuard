package com.voiceguard.adapters

import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.port.AudioSourcePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * File-based [AudioSourcePort] that decodes WAV or MP3 audio and emits consecutive
 * 500 ms [AudioChunk] windows, bypassing any microphone or OS audio capture.
 *
 * Decoding is delegated to [AudioSystem] via the javax.sound.sampled SPI mechanism:
 * - **WAV** is handled natively by the JVM.
 * - **MP3** is handled by the `mp3spi` library (com.googlecode.soundlibs:mp3spi) which
 *   auto-registers itself as an SPI provider — no conditional logic required here.
 *
 * Multi-channel files are reduced to mono by retaining the left (first) channel only,
 * matching the telephony context where a single speech stream is the norm.
 *
 * Replaying the same file always produces an identical [AudioChunk] sequence, guaranteeing
 * deterministic evaluation runs (AC3 Story 3.1).
 *
 * @param audioFile Source audio file — must exist and be readable.
 */
class DatasetAudioSource(private val audioFile: File) : AudioSourcePort {

    init {
        require(audioFile.exists()) { "Audio file not found: ${audioFile.absolutePath}" }
        require(audioFile.canRead()) { "Audio file not readable: ${audioFile.absolutePath}" }
    }

    override fun audioStream(): Flow<AudioChunk> = flow {
        val (pcmBytes, sampleRate) = decodeToPcmMono(audioFile)

        // 500 ms window = sampleRate / 2 samples; each sample is 2 bytes (16-bit)
        val bytesPerChunk = (sampleRate / 2) * 2

        var offset = 0
        while (offset < pcmBytes.size) {
            val end = minOf(offset + bytesPerChunk, pcmBytes.size)
            emit(
                AudioChunk(
                    pcmData = pcmBytes.copyOfRange(offset, end).toNormalizedFloats(),
                    sampleRate = sampleRate
                )
            )
            offset = end
        }
    }

    /**
     * Decodes the audio file to signed 16-bit little-endian PCM mono using [AudioSystem].
     *
     * Two-step conversion when necessary:
     * 1. Source format → signed 16-bit PCM (preserving channels and sample rate).
     * 2. Multi-channel → mono by extracting the left channel from each interleaved frame.
     */
    private fun decodeToPcmMono(file: File): Pair<ByteArray, Int> {
        AudioSystem.getAudioInputStream(file).use { sourceStream ->
            val srcFormat = sourceStream.format
            val sampleRate = srcFormat.sampleRate.toInt()

            val pcmFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                srcFormat.sampleRate,
                BITS_PER_SAMPLE,
                srcFormat.channels,
                srcFormat.channels * BYTES_PER_SAMPLE,
                srcFormat.sampleRate,
                false  // little-endian — matches ByteBuffer default in toNormalizedFloats()
            )

            AudioSystem.getAudioInputStream(pcmFormat, sourceStream).use { pcmStream ->
                val rawBytes = pcmStream.readBytes()
                val monoBytes = if (srcFormat.channels > 1)
                    extractLeftChannel(rawBytes, srcFormat.channels)
                else
                    rawBytes
                return Pair(monoBytes, sampleRate)
            }
        }
    }

    /**
     * Extracts the left (first) channel from interleaved multi-channel 16-bit PCM bytes.
     * Each frame contains [channels] samples of [BYTES_PER_SAMPLE] bytes; only the first is kept.
     */
    private fun extractLeftChannel(interleaved: ByteArray, channels: Int): ByteArray {
        val frameSize = channels * BYTES_PER_SAMPLE
        val frameCount = interleaved.size / frameSize
        val mono = ByteArray(frameCount * BYTES_PER_SAMPLE)
        for (i in 0 until frameCount) {
            val srcIdx = i * frameSize
            mono[i * 2] = interleaved[srcIdx]
            mono[i * 2 + 1] = interleaved[srcIdx + 1]
        }
        return mono
    }

    /** Converts 16-bit little-endian PCM bytes to normalized floats in [-1.0, 1.0]. */
    private fun ByteArray.toNormalizedFloats(): FloatArray {
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 2) { buffer.short / SHORT_MAX_VALUE }
    }

    companion object {
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = 2
        private const val SHORT_MAX_VALUE = 32768f
    }
}
