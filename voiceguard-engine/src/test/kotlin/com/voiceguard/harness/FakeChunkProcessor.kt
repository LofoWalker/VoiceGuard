package com.voiceguard.harness

import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.DetectionUiState

/**
 * Test double for [ChunkProcessor] that returns a preset final state and optionally
 * simulates processing time via [Thread.sleep] to exercise latency measurement logic.
 *
 * @param finalState       State returned by [currentState] after any number of processed chunks.
 * @param processingDelayMs Real blocking sleep applied inside [processChunk] (in milliseconds).
 *                          Uses [Thread.sleep] so [System.nanoTime] measurements reflect the delay.
 */
class FakeChunkProcessor(
    private val finalState: DetectionUiState,
    private val processingDelayMs: Long = 0L
) : ChunkProcessor {

    override suspend fun processChunk(chunk: AudioChunk) {
        if (processingDelayMs > 0L) {
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(processingDelayMs)
        }
    }

    override fun currentState(): DetectionUiState = finalState
}

