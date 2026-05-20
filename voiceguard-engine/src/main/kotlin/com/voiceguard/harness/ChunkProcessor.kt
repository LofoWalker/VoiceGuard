package com.voiceguard.harness

import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.DetectionUiState
import com.voiceguard.domain.service.DetectionOrchestrator

/**
 * Thin abstraction over [DetectionOrchestrator] that makes [ValidationRunner] testable
 * by allowing fake processors to be injected in unit tests.
 *
 * One instance is created per audio file so that each file benefits from a clean engine state.
 */
interface ChunkProcessor {
    /** Dispatches one [AudioChunk] through the detection pipeline. */
    suspend fun processChunk(chunk: AudioChunk)

    /** Returns the engine state after the last processed chunk. */
    fun currentState(): DetectionUiState
}

/**
 * Production [ChunkProcessor] backed by a real [DetectionOrchestrator].
 * Each file should construct a fresh adapter to start with clean orchestrator state.
 */
class DetectionOrchestratorAdapter(
    private val orchestrator: DetectionOrchestrator
) : ChunkProcessor {
    override suspend fun processChunk(chunk: AudioChunk) = orchestrator.processChunk(chunk)
    override fun currentState(): DetectionUiState = orchestrator.state.value
}

