package com.voiceguard.rules

import com.voiceguard.adapters.FakeSpectralClassifier
import com.voiceguard.domain.context.ConversationContext
import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.port.SpectralClassifierPort
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("SpectralArtifactsRule")
class SpectralArtifactsRuleTest {

    private lateinit var context: ConversationContext
    private val chunk = AudioChunk(FloatArray(800) { 0.1f })

    @BeforeEach
    fun setUp() {
        context = ConversationContext()
    }

    // AC-3: SpectralArtifactsRule is constructable in pure JVM — no Android/native dependency.
    @Test
    fun `should_construct_without_any_native_or_android_library_on_classpath`() {
        val classifier = FakeSpectralClassifier(defaultScore = 0.5f)
        val rule = SpectralArtifactsRule(classifier)
        assertEquals("SpectralArtifactsRule", rule.name)
    }

    // AC-1: classifier score is forwarded as suspicion; confidence starts accumulating.
    @Test
    fun `should_forward_classifier_score_as_suspicion_score`() = runTest {
        val classifier = FakeSpectralClassifier(defaultScore = 0.85f)
        val rule = SpectralArtifactsRule(classifier)

        val result = rule.analyze(chunk, context)

        assertEquals(0.85f, result.suspicionScore, 0.001f, "Suspicion must reflect classifier output")
        assertTrue(result.confidence > 0f, "Confidence must start accumulating after first chunk")
    }

    // AC-2: several AI-scored chunks → increasing confidence, high suspicion.
    @Test
    fun `should_increase_confidence_and_maintain_high_suspicion_across_ai_scored_chunks`() = runTest {
        val aiScores = List(5) { 0.9f }
        val classifier = FakeSpectralClassifier(scores = aiScores, defaultScore = 0.9f)
        val rule = SpectralArtifactsRule(classifier)

        var previousConfidence = 0f
        repeat(5) {
            val result = rule.analyze(chunk, context)
            assertTrue(result.confidence >= previousConfidence, "Confidence must not decrease")
            assertTrue(result.suspicionScore >= 0.8f, "Suspicion must remain high for AI scores")
            previousConfidence = result.confidence
        }
        assertTrue(previousConfidence > 0.4f, "Confidence must have grown meaningfully after 5 chunks")
    }

    // AC-1: human-scored chunks → low suspicion accumulation.
    @Test
    fun `should_accumulate_low_suspicion_for_human_voice_scores`() = runTest {
        val classifier = FakeSpectralClassifier(defaultScore = 0.05f)
        val rule = SpectralArtifactsRule(classifier)

        repeat(5) { rule.analyze(chunk, context) }
        val result = rule.analyze(chunk, context)

        assertTrue(result.suspicionScore < 0.2f, "Human scores must produce low suspicion: ${result.suspicionScore}")
    }

    // AC-3: FakeSpectralClassifier implements SpectralClassifierPort — no runtime inference needed.
    @Test
    fun `FakeSpectralClassifier_implements_SpectralClassifierPort_contract`() {
        val fake: SpectralClassifierPort = FakeSpectralClassifier(scores = listOf(0.1f, 0.9f), defaultScore = 0.5f)
        assertEquals(0.1f, fake.classify(chunk), "First call returns seeded score")
        assertEquals(0.9f, fake.classify(chunk), "Second call returns seeded score")
        assertEquals(0.5f, fake.classify(chunk), "Subsequent calls return defaultScore")
    }
}

