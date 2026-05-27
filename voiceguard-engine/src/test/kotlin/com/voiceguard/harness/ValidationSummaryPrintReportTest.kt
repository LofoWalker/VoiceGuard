package com.voiceguard.harness

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("ValidationSummary — printReport verbosity")
class ValidationSummaryPrintReportTest {

    // Minimal summary with one misclassified AI file (AI predicted as HUMAN).
    private fun summaryWithOneMisclassified(): ValidationSummary {
        val verdict = ValidationVerdict(
            filePath = "/dataset/fake/ai_sample.wav",
            groundTruth = GroundTruthLabel.AI,
            engineVerdict = GroundTruthLabel.HUMAN,
            aiProbability = 0.3f,
            globalConfidence = 0.9f
        )
        return ValidationSummary(
            totalFiles = 1,
            countableVerdicts = 0,
            metricsAvailable = false,
            correctCount = 0,
            accuracy = Float.NaN,
            totalHumanFiles = 0,
            falsePositives = 0,
            falsePositiveRate = Float.NaN,
            totalAiFiles = 1,
            truePositives = 0,
            falseNegatives = 1,
            recall = Float.NaN,
            meanLatencyMs = 0.0,
            maxLatencyMs = 0L,
            budgetViolationCount = 0,
            verdicts = listOf(verdict)
        )
    }

    /** Captures everything printed to [System.out] during [block]. */
    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        val capture = PrintStream(buffer, /* autoFlush = */ true)
        System.setOut(capture)
        try {
            block()
        } finally {
            capture.flush()
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test
    @DisplayName("verbose=false (default) : affiche le compteur, pas le détail par fichier")
    fun nonVerbose_showsCountNotDetail() {
        val output = captureStdout { summaryWithOneMisclassified().printReport(verbose = false) }

        assertTrue(
            output.contains("Fichiers mal classés : 1"),
            "La ligne de compteur doit apparaître en mode non-verbose"
        )
        assertTrue(
            output.contains("-Pverbose") || output.contains("-v"),
            "Un indice de re-lancement doit être présent en mode non-verbose"
        )
        assertFalse(
            output.contains("FICHIERS MAL CLASSÉS"),
            "L'en-tête de la section détaillée ne doit pas apparaître en mode non-verbose"
        )
        assertFalse(
            output.contains("ai_sample.wav"),
            "Les chemins de fichiers ne doivent pas apparaître en mode non-verbose"
        )
    }

    @Test
    @DisplayName("verbose=true : affiche la section détaillée avec les chemins de fichiers")
    fun verbose_showsDetailSection() {
        val output = captureStdout { summaryWithOneMisclassified().printReport(verbose = true) }

        assertTrue(
            output.contains("FICHIERS MAL CLASSÉS"),
            "L'en-tête de la section détaillée doit apparaître en mode verbose"
        )
        assertTrue(
            output.contains("ai_sample.wav"),
            "Le chemin du fichier mal classé doit apparaître en mode verbose"
        )
        assertFalse(
            output.contains("-Pverbose"),
            "Le message d'indice ne doit pas apparaître en mode verbose"
        )
    }
}
