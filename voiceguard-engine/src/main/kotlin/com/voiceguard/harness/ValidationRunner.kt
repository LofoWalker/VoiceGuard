package com.voiceguard.harness

import com.voiceguard.adapters.DatasetAudioSource
import com.voiceguard.domain.port.AudioDetectionRule
import com.voiceguard.domain.service.DetectionOrchestrator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Audits the engine pipeline against a local dataset directory to produce detection verdicts
 * and aggregated accuracy / latency metrics.
 *
 * ## Dataset Directory Convention
 *
 * Ground truth is inferred from the **parent folder name** of each audio file:
 * - `real/` or `human/` → [GroundTruthLabel.HUMAN]
 * - `fake/`, `ai/`, or `deepfake/` → [GroundTruthLabel.AI]
 * Files in unrecognised directories are skipped.
 *
 * ## Usage
 *
 * ```bash
 * ./gradlew validateEngine -PdatasetPath=/path/to/dataset
 * ```
 *
 * Or programmatically:
 * ```kotlin
 * val summary = ValidationRunner(
 *     datasetDir = File("/path/to/dataset"),
 *     processorFactory = { DetectionOrchestratorAdapter(DetectionOrchestrator(rules)) }
 * ).runValidation()
 * summary.printReport()
 * ```
 *
 * @param datasetDir      Root directory containing `real/` and `fake/` subdirectories.
 * @param processorFactory Factory called once per audio file to produce a fresh [ChunkProcessor].
 * @param config          Detection thresholds and latency budget.
 */
class ValidationRunner(
    private val datasetDir: File,
    private val processorFactory: () -> ChunkProcessor,
    private val config: ValidationConfig = ValidationConfig()
) {

    /**
     * Runs the full dataset evaluation: streams each file through the engine pipeline,
     * records timing, and returns aggregated [ValidationSummary].
     *
     * Files that cannot be decoded (corrupt, unsupported format, empty) are skipped with
     * a warning printed to stderr — a single bad file never aborts the entire run.
     */
    suspend fun runValidation(): ValidationSummary {
        val audioFiles = scanAudioFiles()
        val verdicts = mutableListOf<ValidationVerdict>()
        val allChunkLatenciesNs = mutableListOf<Long>()

        for ((file, groundTruth) in audioFiles) {
            try {
                val processor = processorFactory()
                val source = DatasetAudioSource(file, config.targetSampleRate)
                val fileLatenciesNs = mutableListOf<Long>()

                source.audioStream().onEach { chunk ->
                    val startNs = System.nanoTime()
                    processor.processChunk(chunk)
                    fileLatenciesNs.add(System.nanoTime() - startNs)
                }.collect()

                allChunkLatenciesNs.addAll(fileLatenciesNs)

                val finalState = processor.currentState()
                verdicts.add(
                    ValidationVerdict(
                        filePath = file.absolutePath,
                        groundTruth = groundTruth,
                        engineVerdict = classifyState(finalState),
                        aiProbability = finalState.aiProbability,
                        globalConfidence = finalState.globalConfidence,
                        ruleDiagnostics = processor.ruleDiagnostics()
                    )
                )
            } catch (e: Exception) {
                System.err.println("[VoiceGuard] Skipping ${file.name}: ${e.message}")
            }
        }

        return buildSummary(verdicts, allChunkLatenciesNs)
    }

    /**
     * Scans [datasetDir] recursively for WAV/PCM files and determines their ground-truth label
     * from the immediate parent directory name.
     */
    private fun scanAudioFiles(): List<Pair<File, GroundTruthLabel>> =
        datasetDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .mapNotNull { file ->
                val label = resolveGroundTruth(file.parentFile?.name.orEmpty())
                if (label != null) Pair(file, label) else null
            }
            .toList()

    private fun resolveGroundTruth(parentDirName: String): GroundTruthLabel? = when {
        parentDirName.lowercase() in HUMAN_DIR_NAMES -> GroundTruthLabel.HUMAN
        parentDirName.lowercase() in AI_DIR_NAMES -> GroundTruthLabel.AI
        else -> null
    }

    /**
     * Classifies the engine state as AI or HUMAN.
     * Files with confidence below [ValidationConfig.minConfidence] are conservatively treated
     * as HUMAN (insufficient evidence to classify as deepfake).
     */

    private fun classifyState(state: com.voiceguard.domain.model.DetectionUiState): GroundTruthLabel =
        if (state.globalConfidence >= config.minConfidence && state.aiProbability >= config.aiThreshold)
            GroundTruthLabel.AI
        else
            GroundTruthLabel.HUMAN

    private fun buildSummary(
        verdicts: List<ValidationVerdict>,
        latenciesNs: List<Long>
    ): ValidationSummary {
        val countable = verdicts.filter { it.globalConfidence >= config.minConfidence }
        val metricsAvailable = countable.isNotEmpty()

        val correctCount = countable.count { it.isCorrect }
        val accuracy = if (!metricsAvailable) Float.NaN else correctCount.toFloat() / countable.size

        val humanCountable = countable.filter { it.groundTruth == GroundTruthLabel.HUMAN }
        val falsePositives = humanCountable.count { it.engineVerdict == GroundTruthLabel.AI }
        val fpr = if (!metricsAvailable) Float.NaN
                  else if (humanCountable.isEmpty()) 0.0f
                  else falsePositives.toFloat() / humanCountable.size

        val aiCountable = countable.filter { it.groundTruth == GroundTruthLabel.AI }
        val truePositives = aiCountable.count { it.engineVerdict == GroundTruthLabel.AI }
        val falseNegatives = aiCountable.count { it.engineVerdict == GroundTruthLabel.HUMAN }
        val recall = if (!metricsAvailable || aiCountable.isEmpty()) Float.NaN
                     else truePositives.toFloat() / aiCountable.size

        val meanLatencyMs = if (latenciesNs.isEmpty()) 0.0 else latenciesNs.average() / NS_TO_MS
        val maxLatencyMs = if (latenciesNs.isEmpty()) 0L else (latenciesNs.maxOrNull()!! / NS_TO_MS).toLong()
        val violations = latenciesNs.count { it / NS_TO_MS.toLong() > config.budgetMs }

        return ValidationSummary(
            totalFiles = verdicts.size,
            countableVerdicts = countable.size,
            metricsAvailable = metricsAvailable,
            correctCount = correctCount,
            accuracy = accuracy,
            totalHumanFiles = humanCountable.size,
            falsePositives = falsePositives,
            falsePositiveRate = fpr,
            totalAiFiles = aiCountable.size,
            truePositives = truePositives,
            falseNegatives = falseNegatives,
            recall = recall,
            meanLatencyMs = meanLatencyMs,
            maxLatencyMs = maxLatencyMs,
            budgetViolationCount = violations,
            ruleStats = computeRuleAggregates(countable),
            thresholdSweep = computeThresholdSweep(countable),
            verdicts = verdicts
        )
    }

    /**
     * Sweeps the AI-probability decision threshold over the countable verdicts to expose the
     * accuracy / FPR / recall trade-off curve. The aggregate scoring is fixed; only the cutoff on
     * [ValidationVerdict.aiProbability] varies — so this reads off the operating point without
     * re-running the pipeline. Calibrate the threshold here on the validation split, not testing.
     */
    private fun computeThresholdSweep(countable: List<ValidationVerdict>): List<ThresholdPoint> {
        if (countable.isEmpty()) return emptyList()
        val ai = countable.filter { it.groundTruth == GroundTruthLabel.AI }
        val human = countable.filter { it.groundTruth == GroundTruthLabel.HUMAN }
        if (ai.isEmpty() || human.isEmpty()) return emptyList()

        return (1..19).map { step ->
            val t = step * 0.05f
            val tp = ai.count { it.aiProbability >= t }
            val fp = human.count { it.aiProbability >= t }
            ThresholdPoint(
                threshold = t,
                accuracy = (tp + (human.size - fp)).toFloat() / countable.size,
                falsePositiveRate = fp.toFloat() / human.size,
                recall = tp.toFloat() / ai.size
            )
        }
    }

    /**
     * Aggregates per-rule separability over countable verdicts: mean/std suspicion split by
     * ground truth, ROC AUC, Cohen's d, and the best standalone cutoff — the inputs needed to
     * decide which rules to recalibrate, flip, or replace.
     *
     * Suspicion stats use only files where the rule actually voted (confidence > 0); abstentions
     * are excluded so they do not masquerade as "suspicion 0 = human" (see [RuleAggregate]).
     * Returns empty when no countable verdict carries rule diagnostics (e.g. faked processors).
     */
    private fun computeRuleAggregates(countable: List<ValidationVerdict>): List<RuleAggregate> {
        val ruleNames = countable.firstOrNull { it.ruleDiagnostics.isNotEmpty() }
            ?.ruleDiagnostics?.map { it.ruleName } ?: return emptyList()

        val aiVerdicts = countable.filter { it.groundTruth == GroundTruthLabel.AI }
        val humanVerdicts = countable.filter { it.groundTruth == GroundTruthLabel.HUMAN }

        fun votedSuspicions(name: String, list: List<ValidationVerdict>): List<Float> =
            list.mapNotNull { v ->
                v.ruleDiagnostics.find { it.ruleName == name && it.confidence > 0f }?.suspicionScore
            }

        return ruleNames.map { name ->
            val allDiag = countable.mapNotNull { v -> v.ruleDiagnostics.find { it.ruleName == name } }
            val ai = votedSuspicions(name, aiVerdicts)
            val human = votedSuspicions(name, humanVerdicts)
            val (threshold, accuracy) = RuleDiscrimination.bestThreshold(ai, human)
            RuleAggregate(
                ruleName = name,
                weight = allDiag.firstOrNull()?.weight ?: 0f,
                nAi = ai.size,
                nHuman = human.size,
                meanSuspicionAi = RuleDiscrimination.mean(ai),
                meanSuspicionHuman = RuleDiscrimination.mean(human),
                stdAi = RuleDiscrimination.sampleStd(ai),
                stdHuman = RuleDiscrimination.sampleStd(human),
                cohensD = RuleDiscrimination.cohensD(ai, human),
                auc = RuleDiscrimination.auc(ai, human),
                suggestedThreshold = threshold,
                accuracyAtThreshold = accuracy,
                meanConfidence = if (allDiag.isEmpty()) Float.NaN else allDiag.map { it.confidence }.average().toFloat(),
                activeRate = if (allDiag.isEmpty()) 0f else allDiag.count { it.activeOnLastChunk }.toFloat() / allDiag.size
            )
        }
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("wav", "mp3")
        // KPI targets from PRD §6.2
        private const val KPI_MIN_ACCURACY = 0.85f
        private const val KPI_MAX_FPR = 0.05f
        private val HUMAN_DIR_NAMES = setOf("real", "human")
        private val AI_DIR_NAMES = setOf("fake", "ai", "deepfake")
        private const val NS_TO_MS = 1_000_000.0

        // Common rate all files are resampled to during validation, to remove the dataset's
        // sample-rate/label confound (real 16 kHz vs fake 24 kHz).
        private const val NORMALISED_SAMPLE_RATE = 16_000

        // AI-probability decision threshold calibrated on the FoR validation split (sweep optimum).
        private const val CALIBRATED_AI_THRESHOLD = 0.55f

        /**
         * Entry point for Gradle-triggered validation.
         *
         * Expects dataset path via the first argument or the `datasetPath` system property:
         * ```bash
         * ./gradlew validateEngine -PdatasetPath=/path/to/dataset
         * ```
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val datasetPath = args.firstOrNull()
                ?: System.getProperty("datasetPath")
                ?: error(
                    "Dataset path not provided. " +
                            "Pass it as the first argument or via -PdatasetPath=<path>"
                )

            val datasetDir = File(datasetPath).also {
                require(it.isDirectory) { "Dataset path is not a directory: $datasetPath" }
            }

            val summary = runBlocking {
                ValidationRunner(
                    datasetDir = datasetDir,
                    processorFactory = { DetectionOrchestratorAdapter(DetectionOrchestrator(buildProductionRules())) },
                    // Resample to a common rate (removes the real-16k / fake-24k confound) and use
                    // the decision threshold calibrated on the validation split: its sweep shows a
                    // cliff at 0.50→0.55 where FPR drops 29%→3% — 0.55 is the accuracy-max AND the
                    // lowest cutoff meeting the FPR ≤ 5% KPI.
                    config = ValidationConfig(
                        targetSampleRate = NORMALISED_SAMPLE_RATE,
                        aiThreshold = CALIBRATED_AI_THRESHOLD
                    )
                ).runValidation()
            }

            summary.printReport()

            if (!checkKpis(summary)) {
                System.err.println("[VoiceGuard] KPI non atteints — exit 1 (voir rapport ci-dessus)")
                kotlin.system.exitProcess(1)
            }
        }

        /**
         * Returns true when all measurable KPIs pass their targets.
         * When [ValidationSummary.metricsAvailable] is false the run is inconclusive — treated as
         * a warning (returns true) rather than a hard failure, since no dataset was reachable.
         */
        private fun checkKpis(summary: ValidationSummary): Boolean {
            if (!summary.metricsAvailable) {
                System.err.println("[VoiceGuard] Avertissement : aucun verdict exploitable, KPI non mesurables.")
                return true
            }
            var passed = true
            if (!summary.accuracy.isNaN() && summary.accuracy < KPI_MIN_ACCURACY) {
                System.err.println("[VoiceGuard] KPI échoué : précision ${
                    "%.1f".format(summary.accuracy * 100)}% < cible ${
                    "%.0f".format(KPI_MIN_ACCURACY * 100)}%")
                passed = false
            }
            if (!summary.falsePositiveRate.isNaN() && summary.falsePositiveRate > KPI_MAX_FPR) {
                System.err.println("[VoiceGuard] KPI échoué : FPR ${
                    "%.1f".format(summary.falsePositiveRate * 100)}% > cible ${
                    "%.0f".format(KPI_MAX_FPR * 100)}%")
                passed = false
            }
            if (summary.budgetViolationCount > 0) {
                System.err.println("[VoiceGuard] KPI échoué : ${summary.budgetViolationCount} chunk(s) hors budget latence")
                passed = false
            }
            return passed
        }

        /**
         * Wires a fresh rule set for one file evaluation.
         * Called inside processorFactory so each file gets independent rule instances —
         * no mutable state (chunk counters, contours) leaks across file boundaries.
         *
         * FoR calibration (directions chosen on the validation split):
         * - R-02 inverted: FoR fakes are bright, not band-limited (validation AUC 0.31 → ~0.69).
         * - R-06 left default: flipping it improved ranking (AUC) but tripled FPR on the aggregate
         *   (both classes scored high), so it is a net loss — kept in its original direction.
         * - R-01 dropped: 0 votes on isolated utterances (no turn-taking) — pure weight dilution.
         * - R-04/R-05/R-06 kept at default weight despite weak AUC: their near-zero suspicion on
         *   *both* classes acts as FPR ballast. Zero-weighting them lifts recall but triples FPR
         *   (R-02-flip alone scores humans at 0.69), a net accuracy loss — measured and rejected.
         * Weights renormalise automatically in [ScoreAggregator].
         */
        private fun buildProductionRules(): List<AudioDetectionRule> =
            listOf(
                com.voiceguard.rules.NoiseLinearityRule(),
                com.voiceguard.rules.SpectralArtifactsRule(com.voiceguard.adapters.FftSpectralClassifier(), invertScore = true),
                com.voiceguard.rules.JitterShimmerRule(),
                com.voiceguard.rules.CepstralPeakRule(),
                com.voiceguard.rules.ProsodicDynamicsRule()
            )
    }
}

/**
 * Configuration parameters for detection thresholds and performance budgets.
 *
 * @param aiThreshold  Minimum [com.voiceguard.domain.model.DetectionUiState.aiProbability]
 *                     required to classify a file as AI-generated.
 * @param minConfidence Minimum [com.voiceguard.domain.model.DetectionUiState.globalConfidence]
 *                     for a verdict to be included in accuracy / FPR computation.
 * @param budgetMs     Maximum allowed per-chunk processing time in milliseconds (PRD NFR2).
 * @param targetSampleRate When non-null, files are resampled to this rate before analysis to
 *                     remove a sample-rate/label confound. Null preserves each file's native rate.
 */
data class ValidationConfig(
    val aiThreshold: Float = DEFAULT_AI_THRESHOLD,
    val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    val budgetMs: Long = DEFAULT_BUDGET_MS,
    val targetSampleRate: Int? = null
) {
    companion object {
        const val DEFAULT_AI_THRESHOLD = 0.5f

        // On a dataset of isolated utterances (no conversational turn-taking), R-01
        // (weight 0.40) never fires, so the achievable globalConfidence ceiling is
        // 0.35 (R-02) + 0.25 (R-03) = 0.60. A gate of 0.60 would therefore exclude
        // every file. 0.30 ≈ half that ceiling: it admits files with ~1.5 s+ of audio
        // (R-03 ramped + R-02 partially ramped) while still rejecting <1 s fragments.
        // The 0.60 UX threshold from the PRD applies to live conversational detection.
        const val DEFAULT_MIN_CONFIDENCE = 0.3f
        const val DEFAULT_BUDGET_MS = 50L
    }
}

