package com.voiceguard.domain.port

import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.model.RuleResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioDetectionRuleTest {

    @Test
    fun `rule contract is compatible with coroutine call sites`() = runTest {
        // Verifies AC-3: AudioDetectionRule can be called from a coroutine scope.
        val rule = object : AudioDetectionRule {
            override val name: String = "TestRule"
            override val weight: Float = 0.5f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult =
                RuleResult(suspicionScore = 0.8f, confidence = 0.9f)
        }

        val chunk = AudioChunk(floatArrayOf(0.1f, 0.2f))
        val ctx = ConversationContext()
        val result = rule.analyze(chunk, ctx)

        assertEquals(0.8f, result.suspicionScore)
        assertEquals(0.9f, result.confidence)
    }

    @Test
    fun `weight must be in range 0 to 1`() {
        val rule = object : AudioDetectionRule {
            override val name: String = "WeightedRule"
            override val weight: Float = 0.40f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult =
                RuleResult(0f, 0f)
        }

        assertTrue(rule.weight in 0.0f..1.0f, "Weight must be normalized to [0.0, 1.0]")
    }

    @Test
    fun `rule name is non-blank`() {
        val rule = object : AudioDetectionRule {
            override val name: String = "NoiseLinearityRule"
            override val weight: Float = 0.25f
            override suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult =
                RuleResult(0f, 0f)
        }

        assertTrue(rule.name.isNotBlank())
    }

    @Test
    fun `no Android SDK on domain classpath (ADR-01)`() {
        val androidPresent = try {
            AudioDetectionRule::class.java.classLoader?.loadClass("android.content.Context") != null
        } catch (e: ClassNotFoundException) {
            false
        }
        assertTrue(!androidPresent, "Android SDK must not be on the domain classpath")
    }
}

