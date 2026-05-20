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
                val source = DatasetAudioSource(file)
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
                        globalConfidence = finalState.globalConfidence
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
        val correctCount = countable.count { it.isCorrect }
        val accuracy = if (countable.isEmpty()) 1.0f else correctCount.toFloat() / countable.size

        val humanCountable = countable.filter { it.groundTruth == GroundTruthLabel.HUMAN }
        val falsePositives = humanCountable.count { it.engineVerdict == GroundTruthLabel.AI }
        val fpr = if (humanCountable.isEmpty()) 0.0f else falsePositives.toFloat() / humanCountable.size

        val meanLatencyMs = if (latenciesNs.isEmpty()) 0.0
        else latenciesNs.average() / NS_TO_MS
        val maxLatencyMs = if (latenciesNs.isEmpty()) 0L
        else (latenciesNs.maxOrNull()!! / NS_TO_MS).toLong()
        val violations = latenciesNs.count { it / NS_TO_MS.toLong() > config.budgetMs }

        return ValidationSummary(
            totalFiles = verdicts.size,
            countableVerdicts = countable.size,
            correctCount = correctCount,
            accuracy = accuracy,
            totalHumanFiles = humanCountable.size,
            falsePositives = falsePositives,
            falsePositiveRate = fpr,
            meanLatencyMs = meanLatencyMs,
            maxLatencyMs = maxLatencyMs,
            budgetViolationCount = violations,
            verdicts = verdicts
        )
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("wav", "mp3", "pcm")
        private val HUMAN_DIR_NAMES = setOf("real", "human")
        private val AI_DIR_NAMES = setOf("fake", "ai", "deepfake")
        private const val NS_TO_MS = 1_000_000.0

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

            val rules = buildProductionRules()
            val summary = runBlocking {
                ValidationRunner(
                    datasetDir = datasetDir,
                    processorFactory = {
                        DetectionOrchestratorAdapter(DetectionOrchestrator(rules))
                    }
                ).runValidation()
            }

            summary.printReport()
        }

        /**
         * Wires the full rule set used in Phase 1 JVM validation.
         *
         * TFLiteSpectralAdapter is constructed with hardwareAccelerationAvailable=true so the
         * Phase 1 placeholder (neutral 0.5 score) runs without an NNAPI/GPU delegate check —
         * acceptable because this is a JVM validation harness, not an Android deployment.
         */
        private fun buildProductionRules(): List<AudioDetectionRule> {
            val spectralAdapter = com.voiceguard.adapters.TFLiteSpectralAdapter(
                hardwareAccelerationAvailable = true
            )
            return listOf(
                com.voiceguard.rules.NoiseLinearityRule(),
                com.voiceguard.rules.LatencyBehaviorRule(),
                com.voiceguard.rules.SpectralArtifactsRule(spectralAdapter)
            )
        }
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
 */
data class ValidationConfig(
    val aiThreshold: Float = DEFAULT_AI_THRESHOLD,
    val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    val budgetMs: Long = DEFAULT_BUDGET_MS
) {
    companion object {
        const val DEFAULT_AI_THRESHOLD = 0.5f
        const val DEFAULT_MIN_CONFIDENCE = 0.6f
        const val DEFAULT_BUDGET_MS = 50L
    }
}

