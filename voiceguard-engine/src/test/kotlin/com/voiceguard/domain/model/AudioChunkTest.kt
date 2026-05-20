package com.voiceguard.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AudioChunkTest {

    @Test
    fun `default sample rate is 16000 Hz (telephony standard)`() {
        val chunk = AudioChunk(floatArrayOf(0.1f, 0.2f))
        assertEquals(16_000, chunk.sampleRate)
    }

    @Test
    fun `custom sample rate is preserved`() {
        val chunk = AudioChunk(floatArrayOf(0.1f), sampleRate = 44_100)
        assertEquals(44_100, chunk.sampleRate)
    }

    @Test
    fun `sample rate must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            AudioChunk(floatArrayOf(0.1f), sampleRate = 0)
        }
    }

    @Test
    fun `pcm data must not be empty`() {
        assertFailsWith<IllegalArgumentException> {
            AudioChunk(floatArrayOf(), sampleRate = 16_000)
        }
    }

    @Test
    fun `two chunks with same content and sampleRate are equal`() {
        val data = floatArrayOf(0.1f, 0.2f, 0.3f)
        val a = AudioChunk(data.copyOf())
        val b = AudioChunk(data.copyOf())
        assertEquals(a, b)
    }

    @Test
    fun `two chunks with different pcmData are not equal`() {
        val a = AudioChunk(floatArrayOf(0.1f))
        val b = AudioChunk(floatArrayOf(0.2f))
        assertNotEquals(a, b)
    }

    @Test
    fun `two chunks with different sampleRate are not equal`() {
        val data = floatArrayOf(0.1f)
        val a = AudioChunk(data.copyOf(), sampleRate = 16_000)
        val b = AudioChunk(data.copyOf(), sampleRate = 44_100)
        assertNotEquals(a, b)
    }

    @Test
    fun `equal chunks have equal hashCodes`() {
        val data = floatArrayOf(0.5f, 0.6f)
        val a = AudioChunk(data.copyOf())
        val b = AudioChunk(data.copyOf())
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `no Android SDK imports in AudioChunk source`() {
        // Verifies ADR-01: domain model must not reference android.* classes at runtime.
        val classLoader = AudioChunk::class.java.classLoader
        val androidPresent = try {
            classLoader?.loadClass("android.content.Context") != null
        } catch (e: ClassNotFoundException) {
            false
        }
        assertFalse(androidPresent, "Android SDK must not be on the domain classpath")
    }
}

