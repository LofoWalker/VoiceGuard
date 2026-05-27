package com.voiceguard.harness

/**
 * Per-rule performance aggregated over the countable verdicts of a run.
 *
 * Suspicion statistics ([meanSuspicionAi], [stdAi], [auc], …) are computed only over files where
 * the rule **actually voted** (confidence > 0). A rule that abstained on a file (e.g. R-01 on an
 * isolated utterance with no turn-switching) is excluded there rather than scored as "suspicion 0",
 * so the separability metrics are not diluted by abstentions.
 *
 * @param ruleName             Rule identifier.
 * @param weight               Rule weight in the scoring formula.
 * @param nAi                  AI files where the rule voted (sample size behind the AI stats).
 * @param nHuman               HUMAN files where the rule voted.
 * @param meanSuspicionAi      Mean suspicion on AI files (NaN if the rule never voted on one).
 * @param meanSuspicionHuman   Mean suspicion on HUMAN files (NaN if none).
 * @param stdAi                Sample standard deviation of suspicion on AI files (NaN if n < 2).
 * @param stdHuman             Sample standard deviation of suspicion on HUMAN files (NaN if n < 2).
 * @param cohensD              Effect size (mean gap normalised by pooled std); sign = direction.
 * @param auc                  ROC AUC: 0.5 = useless, 1.0 = perfect, < 0.5 = inverted direction.
 * @param suggestedThreshold   Suspicion cutoff maximising this rule's standalone accuracy.
 * @param accuracyAtThreshold  Standalone accuracy of the rule at [suggestedThreshold].
 * @param meanConfidence       Mean confidence this rule reported across all countable files.
 * @param activeRate           Fraction of files where the rule ran on the final chunk (not suppressed).
 */
data class RuleAggregate(
    val ruleName: String,
    val weight: Float,
    val nAi: Int,
    val nHuman: Int,
    val meanSuspicionAi: Float,
    val meanSuspicionHuman: Float,
    val stdAi: Float,
    val stdHuman: Float,
    val cohensD: Float,
    val auc: Float,
    val suggestedThreshold: Float,
    val accuracyAtThreshold: Float,
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

    /**
     * Per-rule discrimination table: suspicion split by ground truth plus separability metrics
     * (AUC, Cohen's d) and the best standalone cutoff. Stats use only files where the rule voted.
     */
    private fun printRuleStats() {
        if (ruleStats.isEmpty()) return
        println()
        println("ANALYSE PAR RÈGLE — pouvoir discriminant (votes réels sur verdicts exploitables)")
        println("  Règle                    Poids  n IA/HUM   IA±σ          HUM±σ          AUC     d       Seuil   Préc.   Verdict")
        ruleStats.forEach { r ->
            println(
                "  " + r.ruleName.padEnd(24) + " " +
                        "%.2f".format(r.weight).padStart(5) + "  " +
                        "${r.nAi}/${r.nHuman}".padEnd(9) + "  " +
                        meanStd(r.meanSuspicionAi, r.stdAi).padEnd(13) + " " +
                        meanStd(r.meanSuspicionHuman, r.stdHuman).padEnd(13) + "  " +
                        fmt(r.auc).padStart(5) + "  " +
                        fmtSigned(r.cohensD).padStart(6) + "  " +
                        fmt(r.suggestedThreshold).padStart(5) + "  " +
                        pct(r.accuracyAtThreshold).padStart(5) + "   " +
                        verdict(r.auc)
            )
        }
        println("  AUC≈0,50 ⇒ règle non discriminante · AUC<0,45 ⇒ direction à inverser · d = écart normalisé (Cohen).")
        println("  Seuil = coupure de suspicion maximisant la précision de la règle seule ; Préc. = précision à ce seuil.")
    }

    private fun fmt(v: Float): String = if (v.isNaN()) "N/A" else "%.2f".format(v)
    private fun fmtSigned(v: Float): String = if (v.isNaN()) "N/A" else "%+.2f".format(v)
    private fun pct(v: Float): String = if (v.isNaN()) "N/A" else "${"%.0f".format(v * 100)}%"

    /** "mean±σ", or just the mean when σ is undefined (n < 2), or "N/A" when the rule never voted. */
    private fun meanStd(m: Float, s: Float): String = when {
        m.isNaN() -> "N/A"
        s.isNaN() -> "%.2f".format(m)
        else -> "%.2f±%.2f".format(m, s)
    }

    private fun verdict(auc: Float): String = when {
        auc.isNaN() -> "n/a"
        auc < 0.45f -> "INVERSER"
        auc < 0.60f -> "FAIBLE"
        auc < 0.75f -> "moyen"
        else -> "FORT"
    }
}

