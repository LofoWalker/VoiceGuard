package com.voiceguard.harness

import com.voiceguard.domain.service.DetectionOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// Objective functions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Criterion used to rank weight combinations during a sweep.
 *
 * @see WeightSweepRunner
 */
enum class SweepObjective {

    /**
     * Maximise recall while keeping FPR ≤ [ValidationConfig.KPI_MAX_FPR].
     *
     * Combinations that violate the FPR KPI receive a negative score so they sort below
     * all compliant entries; ties are broken by raw accuracy.  This is the default
     * objective because FPR ≤ 5% is a hard business constraint.
     */
    FPR_CONSTRAINED_RECALL {
        override fun score(s: ValidationSummary): Float {
            if (!s.metricsAvailable) return Float.NEGATIVE_INFINITY
            if (s.falsePositiveRate > ValidationConfig.KPI_MAX_FPR) {
                // Put violators below zero; rank them by how close they are to compliance.
                return -1f + (ValidationConfig.KPI_MAX_FPR / s.falsePositiveRate.coerceAtLeast(1e-4f)) * 0.5f
            }
            return s.recall + s.accuracy * 0.001f   // recall primary, accuracy tiebreaker
        }
        override fun label() = "recall max (FPR ≤ ${(ValidationConfig.KPI_MAX_FPR * 100).toInt()}%)"
    },

    /** Maximise raw accuracy over countable verdicts — no FPR constraint. */
    ACCURACY {
        override fun score(s: ValidationSummary): Float =
            if (!s.metricsAvailable) Float.NEGATIVE_INFINITY else s.accuracy
        override fun label() = "accuracy max"
    },

    /**
     * Maximise F1 score (harmonic mean of precision and recall).
     *
     * Useful when both false positives and false negatives carry equal cost.
     */
    F1 {
        override fun score(s: ValidationSummary): Float {
            if (!s.metricsAvailable) return Float.NEGATIVE_INFINITY
            val tp = s.truePositives.toFloat()
            val fp = s.falsePositives.toFloat()
            val fn = s.falseNegatives.toFloat()
            val prec = if (tp + fp == 0f) 0f else tp / (tp + fp)
            val rec  = if (tp + fn == 0f) 0f else tp / (tp + fn)
            return if (prec + rec == 0f) 0f else 2f * prec * rec / (prec + rec)
        }
        override fun label() = "F1 max"
    };

    abstract fun score(summary: ValidationSummary): Float
    abstract fun label(): String
}

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

/** One weight-sweep trial: the tested [weights], the full validation outcome, and the [score]. */
data class WeightSweepResult(
    val weights: RuleWeightConfig,
    val summary: ValidationSummary,
    val score: Float
)

/**
 * Full results of one weight sweep, sorted best → worst by [objective].
 *
 * @param results            All evaluated combinations, descending by score.
 * @param productionBaseline Separate evaluation of [RuleWeightConfig.PRODUCTION] for comparison.
 * @param objective          Criterion that was optimised.
 * @param nRunsRequested     Number of combinations requested by the caller.
 * @param step               Discretisation step used for weight generation.
 * @param parallelism        Number of combinations that were evaluated concurrently.
 */
data class WeightSweepSummary(
    val results: List<WeightSweepResult>,
    val productionBaseline: WeightSweepResult,
    val objective: SweepObjective,
    val nRunsRequested: Int,
    val step: Float,
    val parallelism: Int
) {
    /** The best-scoring combination found. */
    val best: WeightSweepResult get() = results.first()

    // ── Report ───────────────────────────────────────────────────────────────

    /**
     * Prints a human-readable sweep report to [System.out].
     *
     * Sections:
     * 1. Header with run parameters.
     * 2. Best combination found.
     * 3. Production baseline.
     * 4. Delta (best − baseline).
     * 5. Top-[TOP_N] table across all runs.
     * 6. Recommendation (snippet to paste into [RuleWeightConfig]).
     */
    fun printReport() {
        val sep = "=".repeat(72)
        println(sep)
        println("VOICEGUARD — RAPPORT DE SWEEP DES POIDS")
        println(sep)
        println("Objectif        : ${objective.label()}")
        println("Runs effectués  : ${results.size}  (demandés : $nRunsRequested)")
        println("Pas de grille   : ${"%.2f".format(step)}")
        println("Parallélisme    : $parallelism workers")
        println()

        if (results.isEmpty()) {
            println("Aucun résultat — dataset vide ou aucun verdict exploitable.")
            println(sep)
            return
        }

        // ── Best ─────────────────────────────────────────────────────────────
        println("MEILLEURE COMBINAISON  ★")
        printResultBlock(best)
        println()

        // ── Baseline ─────────────────────────────────────────────────────────
        println("BASELINE PRODUCTION")
        printResultBlock(productionBaseline)
        println()

        // ── Delta ─────────────────────────────────────────────────────────────
        if (best.summary.metricsAvailable && productionBaseline.summary.metricsAvailable) {
            val dAcc    = best.summary.accuracy          - productionBaseline.summary.accuracy
            val dFpr    = best.summary.falsePositiveRate  - productionBaseline.summary.falsePositiveRate
            val dRecall = best.summary.recall            - productionBaseline.summary.recall
            println("DELTA  (meilleur − baseline)")
            println("  Accuracy  : ${signedPp(dAcc)}")
            println("  FPR       : ${signedPp(dFpr)}  ← négatif = amélioration")
            println("  Recall    : ${signedPp(dRecall)}")
            println()
        }

        // ── Top-N table ───────────────────────────────────────────────────────
        val top = results.take(TOP_N.coerceAtMost(results.size))
        println("TOP ${top.size} / ${results.size} COMBINAISONS")
        println("  Rang  Accuracy   FPR      Recall   Score     Poids")
        top.forEachIndexed { i, r ->
            val marker = if (i == 0) "★" else " "
            println(
                "  $marker${(i + 1).toString().padStart(3)}  " +
                pct(r.summary.accuracy).padStart(7) + "   " +
                pct(r.summary.falsePositiveRate).padStart(6) + "  " +
                pct(r.summary.recall).padStart(6) + "  " +
                "%.4f".format(r.score).padStart(8) + "   " +
                r.weights
            )
        }
        println()

        // ── Recommendation ────────────────────────────────────────────────────
        println("RECOMMANDATION — pour appliquer la meilleure combinaison :")
        println("  Mettre à jour RuleWeightConfig.PRODUCTION dans RuleWeightConfig.kt :")
        with(best.weights) {
            println("    noiseLinearity             = ${"%.2f".format(noiseLinearity)}f")
            println("    spectralArtifacts          = ${"%.2f".format(spectralArtifacts)}f")
            println("    jitterShimmer              = ${"%.2f".format(jitterShimmer)}f")
            println("    cepstralPeak               = ${"%.2f".format(cepstralPeak)}f")
            println("    prosodicDynamics           = ${"%.2f".format(prosodicDynamics)}f")
            println("    microPauseDistribution     = ${"%.2f".format(microPauseDistribution)}f")
            println("    emotionalVariance          = ${"%.2f".format(emotionalVariance)}f")
            println("    turnTakingLatencyVariance  = ${"%.2f".format(turnTakingLatencyVariance)}f")
            println("    harmonicConsistency        = ${"%.2f".format(harmonicConsistency)}f")
            println("    energyEnvelope             = ${"%.2f".format(energyEnvelope)}f")
            println("    roomResponse               = ${"%.2f".format(roomResponse)}f")
            println("    codecArtifact              = ${"%.2f".format(codecArtifact)}f")
            println("    humanImperfection          = ${"%.2f".format(humanImperfection)}f")
            println("    speechEntropy              = ${"%.2f".format(speechEntropy)}f")
        }
        println(sep)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun printResultBlock(r: WeightSweepResult) {
        println("  Poids   : ${r.weights}")
        if (!r.summary.metricsAvailable) {
            println("  Métriques : N/A — aucun verdict exploitable")
            return
        }
        println("  Accuracy  : ${pct(r.summary.accuracy)}  (cible ≥ 85%)")
        println("  FPR       : ${pct(r.summary.falsePositiveRate)}  (cible ≤ 5%)")
        println("  Recall    : ${pct(r.summary.recall)}")
        println("  Score     : ${"%.4f".format(r.score)}")
    }

    private fun pct(v: Float) = if (v.isNaN()) "N/A" else "${"%.1f".format(v * 100)}%"
    private fun signedPp(v: Float) = if (v.isNaN()) "N/A" else
        "${if (v >= 0) "+" else ""}${"%.1f".format(v * 100)} pp"

    companion object {
        private const val TOP_N = 10
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sweep engine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Sweeps the weight space for the five production rules over [nRuns] randomly-sampled
 * combinations, evaluates each against a local dataset, and reports the combination that
 * maximises the chosen [objective].
 *
 * ## Parallelism
 *
 * Up to [parallelism] combinations are evaluated concurrently using Kotlin coroutines on
 * [Dispatchers.Default].  Each coroutine runs a full [ValidationRunner] independently —
 * no shared mutable state between workers.  On an 8-core / NVMe machine, `parallelism=8`
 * gives ~7× wall-clock speedup vs sequential execution.
 *
 * ## Weight generation
 *
 * Each of the five rule weights is drawn independently and uniformly from the set
 * `{step, 2×step, …, 1.0}`.  Since [com.voiceguard.domain.service.ScoreAggregator]
 * normalises by the weighted-confidence denominator, only the *ratios* of weights matter;
 * there is no constraint that weights must sum to 1.
 *
 * ## Algorithm
 *
 * 1. Generate [nRuns] distinct weight combinations (deduplicated).
 * 2. Evaluate up to [parallelism] combinations concurrently via `async`/`awaitAll`.
 * 3. Score each result with [objective].
 * 4. Sort descending by score — keep *all* results.
 * 5. Evaluate [RuleWeightConfig.PRODUCTION] separately as the baseline.
 * 6. Return a [WeightSweepSummary] containing all results + the baseline.
 *
 * ## Usage (Gradle)
 * ```bash
 * ./gradlew :voiceguard-engine:sweepWeights \
 *     -PdatasetPath=/path/to/dataset \
 *     -PnRuns=100 \
 *     -Pstep=0.05 \
 *     -Pparallelism=8
 * ```
 *
 * ## Usage (programmatic)
 * ```kotlin
 * val report = WeightSweepRunner(
 *     datasetDir  = File("/path/to/dataset"),
 *     nRuns       = 100,
 *     parallelism = 8,
 *     objective   = SweepObjective.FPR_CONSTRAINED_RECALL
 * ).run()
 * report.printReport()
 * ```
 *
 * @param datasetDir       Root directory with `real/` and `fake/` sub-directories.
 * @param nRuns            Number of weight combinations to evaluate.
 * @param step             Discretisation step for weight values; smaller = finer grid, more
 *                         distinct combinations, but the same [nRuns] limit still applies.
 * @param parallelism      Maximum number of combinations evaluated concurrently.
 *                         Defaults to [DEFAULT_PARALLELISM] (= physical core count).
 * @param objective        Criterion used to rank combinations.
 * @param validationConfig Detection thresholds forwarded to each [ValidationRunner].
 * @param seed             PRNG seed — fix it to get reproducible sweeps.
 */
class WeightSweepRunner(
    private val datasetDir: File,
    private val nRuns: Int = DEFAULT_N_RUNS,
    private val step: Float = DEFAULT_STEP,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val objective: SweepObjective = SweepObjective.FPR_CONSTRAINED_RECALL,
    private val validationConfig: ValidationConfig = ValidationConfig(
        targetSampleRate = 16_000,
        aiThreshold = DEFAULT_AI_THRESHOLD
    ),
    private val seed: Long = DEFAULT_SEED
) {

    /**
     * Runs the full weight sweep and returns a [WeightSweepSummary] sorted by
     * [objective] (best first).
     *
     * Up to [parallelism] combinations are evaluated concurrently.  Progress is reported
     * via a live ASCII bar updated on each completion (thread-safe via [AtomicInteger]).
     */
    fun run(): WeightSweepSummary {
        val combinations = generateCombinations()
        val total = combinations.size
        val completed = AtomicInteger(0)

        // ── Parallel evaluation ───────────────────────────────────────────────
        val results: List<WeightSweepResult> = runBlocking {
            val dispatcher = Dispatchers.Default.limitedParallelism(parallelism)
            combinations
                .map { weights ->
                    async(dispatcher) {
                        val summary = evaluate(weights)
                        printProgressBar(completed.incrementAndGet(), total)
                        WeightSweepResult(weights, summary, objective.score(summary))
                    }
                }
                .awaitAll()
        }
        println()  // terminate progress bar line

        val sorted = results.sortedByDescending { it.score }

        // ── Baseline (sequential, after all sweep runs) ───────────────────────
        print("\r[WeightSweep] Évaluation baseline production…")
        System.out.flush()
        val baselineSummary = runBlocking { evaluate(RuleWeightConfig.PRODUCTION) }
        println()

        return WeightSweepSummary(
            results            = sorted,
            productionBaseline = WeightSweepResult(
                RuleWeightConfig.PRODUCTION,
                baselineSummary,
                objective.score(baselineSummary)
            ),
            objective          = objective,
            nRunsRequested     = nRuns,
            step               = step,
            parallelism        = parallelism
        )
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Evaluates [ValidationRunner] once for the given [weights].
     * Suspend — called from within a coroutine; no nested [runBlocking].
     */
    private suspend fun evaluate(weights: RuleWeightConfig): ValidationSummary =
        ValidationRunner(
            datasetDir = datasetDir,
            processorFactory = {
                DetectionOrchestratorAdapter(
                    DetectionOrchestrator(ValidationRunner.buildProductionRules(weights))
                )
            },
            config = validationConfig
        ).runValidation()

    /**
     * Renders a live ASCII progress bar to stderr.
     *
     * Example output (40-char bar, 12 of 25 done, parallelism 8):
     * ```
     * [WeightSweep] [████████████████░░░░░░░░] 12/25  (×8)
     * ```
     *
     * Called from multiple coroutines — [AtomicInteger] guarantees the [completed] count
     * is accurate; the rendered bar may occasionally show a slightly stale fraction due to
     * interleaving, which is acceptable for a progress indicator.
     */
    private fun printProgressBar(completed: Int, total: Int) {
        val fraction = completed.toDouble() / total
        val filled   = (fraction * BAR_WIDTH).roundToInt()
        val bar      = "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled)
        print("\r[WeightSweep] [$bar] $completed/$total  (×$parallelism)")
        System.out.flush()
    }

    /**
     * Generates at most [nRuns] distinct weight combinations.
     *
     * Each weight is drawn from the discrete set `{step, 2×step, …, 1.0}`.
     * The [RuleWeightConfig.PRODUCTION] combination is excluded from the sweep results
     * because it is evaluated separately as the baseline.
     */
    private fun generateCombinations(): List<RuleWeightConfig> {
        val rng    = Random(seed)
        val levels = generateSequence(step) { it + step }
            .takeWhile { it <= 1.0f + FLOAT_TOLERANCE }
            .map { it.coerceAtMost(1.0f) }
            .toList()

        require(levels.isNotEmpty()) {
            "step=$step produces no levels — use a value in (0, 1]"
        }

        val seen       = mutableSetOf<String>()
        val result     = mutableListOf<RuleWeightConfig>()
        var attempts   = 0
        val maxAttempts = nRuns * MAX_OVERSAMPLE_FACTOR

        while (result.size < nRuns && attempts < maxAttempts) {
            attempts++
            val w = RuleWeightConfig(
                noiseLinearity            = levels.random(rng),
                spectralArtifacts         = levels.random(rng),
                jitterShimmer             = levels.random(rng),
                cepstralPeak              = levels.random(rng),
                prosodicDynamics          = levels.random(rng),
                microPauseDistribution    = levels.random(rng),
                emotionalVariance         = levels.random(rng),
                turnTakingLatencyVariance = levels.random(rng),
                harmonicConsistency       = levels.random(rng),
                energyEnvelope            = levels.random(rng),
                roomResponse              = levels.random(rng),
                codecArtifact             = levels.random(rng),
                humanImperfection         = levels.random(rng),
                speechEntropy             = levels.random(rng)
            )
            if (seen.add(w.toString())) result.add(w)
        }

        if (result.size < nRuns) {
            System.err.println(
                "[WeightSweep] Avertissement : seulement ${result.size} combinaisons distinctes " +
                "générées (${nRuns} demandées) — réduire step ou nRuns."
            )
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gradle / CLI entry point
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val DEFAULT_N_RUNS      = 50
        const val DEFAULT_STEP        = 0.05f
        const val DEFAULT_SEED        = 42L
        const val DEFAULT_AI_THRESHOLD = ValidationConfig.DEFAULT_AI_THRESHOLD
        /** One worker per physical core — optimal for NVMe I/O + light CPU on an 8-core machine. */
        val DEFAULT_PARALLELISM       = Runtime.getRuntime().availableProcessors() / 2

        private const val BAR_WIDTH             = 40
        private const val FLOAT_TOLERANCE       = 1e-4f
        private const val MAX_OVERSAMPLE_FACTOR = 10

        /**
         * Entry point invoked by the `sweepWeights` Gradle task.
         *
         * JVM system properties (forwarded from Gradle properties):
         * - `datasetPath`  — required; path to dataset root directory.
         * - `nRuns`        — optional (default [DEFAULT_N_RUNS]).
         * - `step`         — optional (default [DEFAULT_STEP]).
         * - `parallelism`  — optional (default [DEFAULT_PARALLELISM] = physical cores).
         * - `objective`    — optional; one of `FPR_CONSTRAINED_RECALL`, `ACCURACY`, `F1`
         *                    (case-insensitive, default `FPR_CONSTRAINED_RECALL`).
         * - `seed`         — optional (default [DEFAULT_SEED]).
         *
         * ```bash
         * ./gradlew :voiceguard-engine:sweepWeights \
         *     -PdatasetPath=/path/to/dataset \
         *     -PnRuns=100 \
         *     -Pstep=0.05 \
         *     -Pparallelism=8
         * ```
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val positional = args.filterNot { it.startsWith("-") }

            val datasetPath = positional.firstOrNull()
                ?: System.getProperty("datasetPath")
                ?: error(
                    "Dataset path not provided. " +
                    "Pass it as the first argument or via -PdatasetPath=<path>"
                )

            val nRuns       = System.getProperty("nRuns")?.toIntOrNull()        ?: DEFAULT_N_RUNS
            val step        = System.getProperty("step")?.toFloatOrNull()       ?: DEFAULT_STEP
            val seed        = System.getProperty("seed")?.toLongOrNull()        ?: DEFAULT_SEED
            val parallelism = System.getProperty("parallelism")?.toIntOrNull()  ?: DEFAULT_PARALLELISM
            val aiThreshold = System.getProperty("aiThreshold")?.toFloatOrNull() ?: DEFAULT_AI_THRESHOLD

            val objective = System.getProperty("objective")
                ?.uppercase()
                ?.let { name ->
                    SweepObjective.entries.find { it.name == name }
                        ?: run {
                            System.err.println(
                                "[WeightSweep] Objectif inconnu '$name'. " +
                                "Valeurs valides : ${SweepObjective.entries.map { it.name }}. " +
                                "FPR_CONSTRAINED_RECALL utilisé par défaut."
                            )
                            SweepObjective.FPR_CONSTRAINED_RECALL
                        }
                }
                ?: SweepObjective.FPR_CONSTRAINED_RECALL

            val datasetDir = File(datasetPath).also {
                require(it.isDirectory) { "Dataset path is not a directory: $datasetPath" }
            }

            println(
                "[WeightSweep] Démarrage — $nRuns runs, step=${"%.2f".format(step)}, " +
                "parallelisme=$parallelism, objectif=${objective.label()}, " +
                "aiThreshold=${"%.2f".format(aiThreshold)}, seed=$seed"
            )

            val summary = WeightSweepRunner(
                datasetDir        = datasetDir,
                nRuns             = nRuns,
                step              = step,
                parallelism       = parallelism,
                objective         = objective,
                validationConfig  = ValidationConfig(
                    targetSampleRate = 16_000,
                    aiThreshold      = aiThreshold
                ),
                seed              = seed
            ).run()

            summary.printReport()
        }
    }
}
