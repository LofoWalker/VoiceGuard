package com.voiceguard.domain

import com.voiceguard.domain.model.DetectionUiState
import com.voiceguard.domain.service.DetectionOrchestrator
import com.voiceguard.domain.service.ScoreAggregator
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compile-time and runtime guards enforcing ADR-01: the domain module must remain
 * a pure Kotlin/JVM library with zero Android SDK or call-control dependencies.
 *
 * These tests fail the moment a prohibited import or field is introduced, giving the
 * team an immediate CI signal before any Android build is attempted.
 */
class DomainScopeComplianceTest {

    // AC-2: No android.* class must be loadable from the domain module's classloader.
    @Test
    fun `Android SDK is not present on domain classpath (ADR-01)`() {
        val classLoader = DetectionOrchestrator::class.java.classLoader
        val androidPresent = try {
            classLoader?.loadClass("android.content.Context") != null
        } catch (e: ClassNotFoundException) {
            false
        }
        assertFalse(androidPresent, "android.content.Context must not be reachable from domain classes")
    }

    // AC-2: Telephony / call-control classes must not appear on domain classpath.
    @Test
    fun `telephony call-control API is not present on domain classpath (ADR-01)`() {
        val classLoader = DetectionOrchestrator::class.java.classLoader
        val telephonyPresent = try {
            classLoader?.loadClass("android.telecom.CallScreeningService") != null
        } catch (e: ClassNotFoundException) {
            false
        }
        assertFalse(telephonyPresent, "Call-control APIs must not be reachable from domain classes")
    }

    // AC-2: Domain build.gradle.kts must not declare any Android SDK dependency.
    // This test resolves the engine module gradle file robustly to avoid false-green runs.
    @Test
    fun `build_gradle_kts contains no android dependency declaration (ADR-01)`() {
        val gradleFile = resolveEngineGradleFile()
        assertTrue(
            gradleFile.exists(),
            "Could not locate engine build.gradle.kts. Tried root/module-relative candidates."
        )

        val content = gradleFile.readText()
        assertFalse(
            content.contains("com.android"),
            "${gradleFile.path} must not declare com.android dependencies in the engine module"
        )
    }

    // AC-1: DetectionUiState must expose only informational numeric fields — no action flags.
    @Test
    fun `DetectionUiState has no call-action or control fields`() {
        val fields = DetectionUiState::class.java.declaredFields.map { it.name }
        val prohibitedNames = listOf(
            "terminate", "block", "interrupt", "hangup", "action", "control", "flag"
        )
        val violations = fields.filter { fieldName ->
            prohibitedNames.any { prohibited -> fieldName.lowercase().contains(prohibited) }
        }
        assertTrue(
            violations.isEmpty(),
            "DetectionUiState must not contain call-action fields. Found: $violations"
        )
    }

    // AC-1: DetectionUiState exposes exactly the three informational fields defined in the architecture.
    @Test
    fun `DetectionUiState contains only the three expected informational fields`() {
        val fields = DetectionUiState::class.java.declaredFields.map { it.name }.toSet()
        val expected = setOf("globalConfidence", "aiProbability", "elapsedSeconds")
        assertTrue(
            fields == expected,
            "DetectionUiState must contain exactly $expected. Found: $fields"
        )
    }

    // AC-2: Domain service classes must not import or reference any Android API.
    @Test
    fun `DetectionOrchestrator class has no Android supertype or interface`() {
        val superTypes = generateSequence<Class<*>>(DetectionOrchestrator::class.java) { it.superclass }
            .map { it.name }
            .toList()
        assertFalse(
            superTypes.any { it.startsWith("android.") },
            "DetectionOrchestrator must not extend an Android class"
        )

        val interfaces = DetectionOrchestrator::class.java.interfaces.map { it.name }
        assertFalse(
            interfaces.any { it.startsWith("android.") },
            "DetectionOrchestrator must not implement an Android interface"
        )
    }

    // AC-2: ScoreAggregator must be a pure JVM class with no Android dependency.
    @Test
    fun `ScoreAggregator has no Android supertype or interface`() {
        val superTypes = generateSequence<Class<*>>(ScoreAggregator::class.java) { it.superclass }
            .map { it.name }.toList()
        assertFalse(superTypes.any { it.startsWith("android.") })

        val interfaces = ScoreAggregator::class.java.interfaces.map { it.name }
        assertFalse(interfaces.any { it.startsWith("android.") })
    }

    private fun resolveEngineGradleFile(): File {
        val candidates = listOf(
            File("voiceguard-engine/build.gradle.kts"),
            File("build.gradle.kts"),
            File("../voiceguard-engine/build.gradle.kts")
        )
        return candidates.firstOrNull { candidate ->
            candidate.exists() && candidate.readText().contains("kotlinx-coroutines-core")
        } ?: candidates.first()
    }
}

