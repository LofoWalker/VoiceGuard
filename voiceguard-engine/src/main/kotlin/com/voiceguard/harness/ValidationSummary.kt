package com.voiceguard.harness

/**
 * Aggregated outcome of one full validation run across a dataset directory.
 *
 * Accuracy and FPR are computed only over verdicts where [ValidationVerdict.globalConfidence]
 * is above [ValidationConfig.minConfidence]; low-confidence outputs are excluded to avoid
 * penalizing the engine for inconclusive warm-up windows.
 *
 * @param totalFiles          Total audio files evaluated.
 * @param countableVerdicts   Verdicts with sufficient confidence to count toward metrics.
 * @param correctCount        Correctly classified samples among countable verdicts.
 * @param accuracy            Correct / countable; 1.0 when no countable verdicts exist.
 * @param totalHumanFiles     Human files among countable verdicts.
 * @param falsePositives      Human files misclassified as AI.
 * @param falsePositiveRate   falsePositives / totalHumanFiles; 0.0 when no human files exist.
 * @param meanLatencyMs       Mean per-chunk processing time in milliseconds.
 * @param maxLatencyMs        Peak per-chunk processing time in milliseconds.
 * @param budgetViolationCount Chunks where processing exceeded the 50 ms budget.
 * @param verdicts            Full list of per-file outcomes.
 */
data class ValidationSummary(
    val totalFiles: Int,
    val countableVerdicts: Int,
    val correctCount: Int,
    val accuracy: Float,
    val totalHumanFiles: Int,
    val falsePositives: Int,
    val falsePositiveRate: Float,
    val meanLatencyMs: Double,
    val maxLatencyMs: Long,
    val budgetViolationCount: Int,
    val verdicts: List<ValidationVerdict>
) {
    /**
     * Prints a human-readable summary to [System.out].
     * Lists all misclassified files for easy triage.
     */
    fun printReport() {
        val separator = "=" .repeat(60)
        println(separator)
        println("VOICEGUARD VALIDATION REPORT")
        println(separator)
        println("Total files evaluated : $totalFiles")
        println("Countable verdicts    : $countableVerdicts")
        println("Accuracy              : ${"%.1f".format(accuracy * 100)}%  (target ≥ 85%)")
        println("False positive rate   : ${"%.1f".format(falsePositiveRate * 100)}%  (target ≤ 5%)")
        println()
        println("LATENCY")
        println("  Mean per chunk : ${"%.2f".format(meanLatencyMs)} ms")
        println("  Max per chunk  : $maxLatencyMs ms")
        println("  Budget (50 ms) violations : $budgetViolationCount")
        println()

        val misclassified = verdicts.filter { !it.isCorrect }
        if (misclassified.isEmpty()) {
            println("No misclassified files.")
        } else {
            println("MISCLASSIFIED FILES (${misclassified.size})")
            misclassified.forEach { v ->
                println(
                    "  [${v.groundTruth} → ${v.engineVerdict}] prob=${
                        "%.2f".format(v.aiProbability)
                    } conf=${"%.2f".format(v.globalConfidence)}  ${v.filePath}"
                )
            }
        }
        println(separator)
    }
}

