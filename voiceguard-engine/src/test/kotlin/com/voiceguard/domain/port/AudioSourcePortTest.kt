package com.voiceguard.domain.port

import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AudioSourcePortTest {

    @Test
    fun `audioStream emits AudioChunk items`() = runTest {
        val fakeSource = object : AudioSourcePort {
            override fun audioStream(): Flow<AudioChunk> = flowOf(
                AudioChunk(floatArrayOf(0.1f)),
                AudioChunk(floatArrayOf(0.2f))
            )
        }

        val chunks = fakeSource.audioStream().toList()
        assertEquals(2, chunks.size)
        assertEquals(16_000, chunks[0].sampleRate)
    }
}

