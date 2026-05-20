package com.voiceguard.domain.context

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationContextTest {

    @Test
    fun `fresh context has no speech switches`() {
        val ctx = ConversationContext()
        assertTrue(ctx.speechSwitchTimestamps.isEmpty())
    }

    @Test
    fun `recordSpeechSwitch appends timestamp in order`() {
        val ctx = ConversationContext()
        ctx.recordSpeechSwitch(1000L)
        ctx.recordSpeechSwitch(2500L)
        assertEquals(listOf(1000L, 2500L), ctx.speechSwitchTimestamps)
    }

    @Test
    fun `lastSpeechSwitchAt returns null when no switches recorded`() {
        val ctx = ConversationContext()
        assertNull(ctx.lastSpeechSwitchAt())
    }

    @Test
    fun `lastSpeechSwitchAt returns most recent timestamp`() {
        val ctx = ConversationContext()
        ctx.recordSpeechSwitch(1000L)
        ctx.recordSpeechSwitch(3000L)
        assertEquals(3000L, ctx.lastSpeechSwitchAt())
    }

    @Test
    fun `updateCallDuration stores elapsed millis`() {
        val ctx = ConversationContext()
        ctx.updateCallDuration(5000L)
        assertEquals(5000L, ctx.callDurationMillis)
    }

    @Test
    fun `speechSwitchTimestamps list is read-only from outside`() {
        val ctx = ConversationContext()
        ctx.recordSpeechSwitch(1000L)
        val timestamps = ctx.speechSwitchTimestamps
        // the returned list must be immutable — modifying it must not affect internal state
        @Suppress("ConstantConditionIf")
        try {
            (timestamps as MutableList<Long>).add(9999L)
        } catch (_: UnsupportedOperationException) {
            // expected: list is truly unmodifiable
        }
        assertEquals(1, ctx.speechSwitchTimestamps.size)
    }
}

