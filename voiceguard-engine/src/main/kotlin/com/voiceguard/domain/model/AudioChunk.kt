package com.voiceguard.domain.model

/**
 * Immutable raw PCM payload representing one analysis window (typically 500 ms).
 *
 * [pcmData] values are normalized floats in [-1.0, 1.0].
 * [sampleRate] defaults to 16 kHz — the telephony standard required by all detection rules.
 *
 * FloatArray equality is value-based via [equals]/[hashCode] so data-class semantics hold correctly.
 */
data class AudioChunk(
    val pcmData: FloatArray,
    val sampleRate: Int = 16_000
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return sampleRate == other.sampleRate && pcmData.contentEquals(other.pcmData)
    }

    override fun hashCode(): Int = 31 * sampleRate + pcmData.contentHashCode()
}

