package com.voiceguard.harness

/**
 * Per-rule performance aggregated over the countable verdicts of a run.
 *
 * @param ruleName           Rule identifier.
 * @param weight             Rule weight in the scoring formula.
 * @param meanSuspicionAi    Mean suspicion this rule reported on AI files (NaN if none).
 * @param meanSuspicionHuman Mean suspicion this rule reported on HUMAN files (NaN if none).
 * @param meanConfidence     Mean confidence this rule reported across all countable files.
 * @param activeRate         Fraction of files where the rule ran on the final chunk (not suppressed).
 */
data class RuleAggregate(
    val ruleName: String,
    val weight: Float,
    val meanSuspicionAi: Float,
    val meanSuspicionHuman: Float,
    val meanConfidence: Float,
    val activeRate: Float
) {
    /** AI-minus-HUMAN suspicion gap: positive ⇒ the rule separates the two classes correctly. */
    val discriminationGap: Float get() = meanSuspicionAi - meanSuspicionHuman
}

/**
 * Aggregated outcome of one full validation run across a dataset directory.
 *
 * Accuracy, FPR, and recall are computed only over verdicts where
 * [ValidationVerdict.globalConfidence] is above [ValidationConfig.minConfidence].
 * When no countable verdicts exist, [metricsAvailable] is false and metric fields carry
 * sentinel values (Float.NaN / -1) — never interpret them as real measurements.
 *
 * Confusion matrix:
 * ```
 *                  Predicted HUMAN   Predicted AI
 * Actual HUMAN       trueNegatives    falsePositives
 * Actual AI          falseNegatives   truePositives
 * ```
 *
 * @param totalFiles          Total audio files evaluated.
 * @param countableVerdicts   Verdicts with sufficient confidence to count toward metrics.
 * @param metricsAvailable    False when countableVerdicts == 0 — all metric fields are N/A.
 * @param correctCount        Correctly classified samples among countable verdicts.
 * @param accuracy            Correct / countable; NaN when [metricsAvailable] is false.
 * @param totalHumanFiles     Human files among countable verdicts.
 * @param falsePositives      Human files misclassified as AI (FP).
 * @param falsePositiveRate   FP / totalHumanFiles; NaN when [metricsAvailable] is false.
 * @param totalAiFiles        AI files among countable verdicts.
 * @param truePositives       AI files correctly classified as AI (TP).
 * @param falseNegatives      AI files misclassified as human (FN).
 * @param recall              TP / totalAiFiles; NaN when no AI files in countable set.
 * @param meanLatencyMs       Mean per-chunk processing time in milliseconds.
 * @param maxLatencyMs        Peak per-chunk processing time in milliseconds.
 * @param budgetViolationCount Chunks where processing exceeded the budget.
 * @param ruleStats           Per-rule aggregated performance over countable verdicts.
 * @param verdicts            Full list of per-file outcomes.
 */
data class ValidationSummary(
    val totalFiles: Int,
    val countableVerdicts: Int,
    val metricsAvailable: Boolean,
    val correctCount: Int,
    val accuracy: Float,
    val totalHumanFiles: Int,
    val falsePositives: Int,
    val falsePositiveRate: Float,
    val totalAiFiles: Int,
    val truePositives: Int,
    val falseNegatives: Int,
    val recall: Float,
    val meanLatencyMs: Double,
    val maxLatencyMs: Long,
    val budgetViolationCount: Int,
    val ruleStats: List<RuleAggregate> = emptyList(),
    val verdicts: List<ValidationVerdict>
) {
    /**
     * Prints a human-readable summary to [System.out].
     * Includes confusion matrix and recall. Lists all misclassified files for triage.
     */
    fun printReport() {
        val sep = "=".repeat(60)
        println(sep)
        println("VOICEGUARD VALIDATION REPORT")
        println(sep)
        println("Total files evaluated : $totalFiles")
        println("Countable verdicts    : $countableVerdicts")
        println()

        if (!metricsAvailable) {
            println("METRICS : N/A — aucun verdict exploitable (confiance insuffisante sur tous les fichiers)")
        } else {
            println("DETECTION METRICS")
            println("  Accuracy          : ${"%.1f".format(accuracy * 100)}%  (cible ≥ 85%)")
            println("  False positive rate: ${"%.1f".format(falsePositiveRate * 100)}%  (cible ≤ 5%)")
            if (totalAiFiles > 0)
                println("  Recall (AI det.)  : ${"%.1f".format(recall * 100)}%")
            else
                println("  Recall (AI det.)  : N/A — aucun fichier AI dans l'ensemble évalué")
            println()
            println("MATRICE DE CONFUSION")
            println("                    Prédit HUMAN   Prédit AI")
            println("  Réel HUMAN        ${(totalHumanFiles - falsePositives).toString().padStart(12)}   ${falsePositives.toString().padStart(9)}")
            println("  Réel AI           ${falseNegatives.toString().padStart(12)}   ${truePositives.toString().padStart(9)}")
            printRuleStats()
        }
        println()
        println("LATENCE")
        println("  Moyenne par chunk : ${"%.2f".format(meanLatencyMs)} ms")
        println("  Max par chunk     : $maxLatencyMs ms")
        println("  Violations budget 50 ms : $budgetViolationCount")
        println()

        val misclassified = verdicts.filter { !it.isCorrect }
        if (misclassified.isEmpty()) {
            println("Aucun fichier mal classé.")
        } else {
            println("FICHIERS MAL CLASSÉS (${misclassified.size})")
            println("  Détail par règle : <initiales> s=suspicion c=confiance (* = inactive au dernier chunk)")
            misclassified.forEach { v ->
                println(
                    "  [${v.groundTruth} → ${v.engineVerdict}]" +
                            " prob=${"%.2f".format(v.aiProbability)}" +
                            " conf=${"%.2f".format(v.globalConfidence)}" +
                            "  ${ruleBreakdown(v)}" +
                            "  ${v.filePath}"
                )
            }
        }
        println(sep)
    }

    /** Compact per-rule breakdown for one verdict, e.g. "LBR s0.00/c0.00* SAR s0.55/c0.83 NLR s0.20/c1.00". */
    private fun ruleBreakdown(verdict: ValidationVerdict): String =
        if (verdict.ruleDiagnostics.isEmpty()) ""
        else verdict.ruleDiagnostics.joinToString(" ") { d ->
            val tag = d.ruleName.filter { it.isUpperCase() }.ifEmpty { d.ruleName.take(3) }
            val inactive = if (d.activeOnLastChunk) "" else "*"
            "$tag s${"%.2f".format(d.suspicionScore)}/c${"%.2f".format(d.confidence)}$inactive"
        }

    /** Per-rule discrimination table: mean suspicion split by ground truth + the AI−HUMAN gap. */
    private fun printRuleStats() {
        if (ruleStats.isEmpty()) return
        println()
        println("ANALYSE PAR RÈGLE (moyennes sur verdicts exploitables)")
        println("  Règle                    Poids   Susp.IA   Susp.HUM   Écart   Conf.moy   Actif")
        ruleStats.forEach { r ->
            println(
                "  ${r.ruleName.padEnd(24)} " +
                        "%.2f".format(r.weight).padStart(5) + "   " +
                        fmt(r.meanSuspicionAi).padStart(7) + "   " +
                        fmt(r.meanSuspicionHuman).padStart(8) + "   " +
                        fmtSigned(r.discriminationGap).padStart(5) + "   " +
                        fmt(r.meanConfidence).padStart(8) + "   " +
                        "${"%.0f".format(r.activeRate * 100)}%".padStart(5)
            )
        }
        println("  Écart > 0 ⇒ la règle attribue plus de suspicion aux vrais fakes (discrimine bien).")
    }

    private fun fmt(v: Float): String = if (v.isNaN()) "N/A" else "%.2f".format(v)
    private fun fmtSigned(v: Float): String = if (v.isNaN()) "N/A" else "%+.2f".format(v)
}

