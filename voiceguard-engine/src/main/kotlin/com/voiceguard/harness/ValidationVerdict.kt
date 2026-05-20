package com.voiceguard.harness

/**
 * Ground truth classification label for a dataset audio sample.
 * Derived from the directory structure of the dataset (e.g. `real/` → HUMAN, `fake/` → AI).
 */
enum class GroundTruthLabel { HUMAN, AI }

/**
 * Per-file detection outcome produced by the validation harness.
 *
 * @param filePath         Absolute path of the evaluated audio file.
 * @param groundTruth      True label inferred from the dataset directory structure.
 * @param engineVerdict    Engine classification based on [aiProbability] vs threshold.
 * @param aiProbability    Final AI probability emitted by the engine for this file.
 * @param globalConfidence Final confidence emitted by the engine; verdicts below
 *                         [ValidationConfig.minConfidence] are not counted in accuracy or FPR.
 */
data class ValidationVerdict(
    val filePath: String,
    val groundTruth: GroundTruthLabel,
    val engineVerdict: GroundTruthLabel,
    val aiProbability: Float,
    val globalConfidence: Float
) {
    val isCorrect: Boolean get() = groundTruth == engineVerdict
}

