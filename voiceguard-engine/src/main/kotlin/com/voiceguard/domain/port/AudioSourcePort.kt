package com.voiceguard.domain.port

import com.voiceguard.domain.model.AudioChunk
import kotlinx.coroutines.flow.Flow

/**
 * Secondary port — abstracts the audio stream provider from the domain.
 *
 * Concrete adapters (microphone capture, dataset replay) implement this interface and are
 * injected into the DetectionOrchestrator. The domain never references audio drivers directly,
 * satisfying ADR-01 (pure-JVM domain).
 */
interface AudioSourcePort {

    /**
     * Returns a cold [Flow] of consecutive [AudioChunk] windows.
     *
     * Each emission represents one analysis window (typically 500 ms of PCM data).
     * The flow completes when the audio source is exhausted or closed.
     */
    fun audioStream(): Flow<AudioChunk>
}

