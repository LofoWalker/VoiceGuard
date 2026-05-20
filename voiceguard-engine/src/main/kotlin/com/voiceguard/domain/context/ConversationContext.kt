package com.voiceguard.domain.context

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe mutable store for conversation-level timing data needed by latency analysis.
 *
 * **Mutation boundary:** ONLY the DetectionOrchestrator may call the write methods
 * ([recordSpeechSwitch], [updateCallDuration]). All detection rules receive this object
 * by reference and must treat it as read-only — mutating it from a rule would create a race
 * condition with parallel coroutine execution (ADR-03).
 */
class ConversationContext {

    private val _speechSwitchTimestamps = CopyOnWriteArrayList<Long>()

    /** Epoch-millisecond timestamps of each detected speaker-turn boundary, in insertion order. */
    val speechSwitchTimestamps: List<Long>
        get() = _speechSwitchTimestamps.toList()

    /** Total elapsed duration of the call in milliseconds. Updated each chunk by the orchestrator. */
    var callDurationMillis: Long = 0L
        private set

    /** Appends a speech-switch event. Called exclusively by the orchestrator. */
    fun recordSpeechSwitch(epochMillis: Long) {
        _speechSwitchTimestamps.add(epochMillis)
    }

    /** Returns the most recent speech-switch timestamp, or null if none have been recorded yet. */
    fun lastSpeechSwitchAt(): Long? = _speechSwitchTimestamps.lastOrNull()

    /** Updates the running call duration. Called exclusively by the orchestrator after each chunk. */
    fun updateCallDuration(durationMillis: Long) {
        callDurationMillis = durationMillis
    }
}

