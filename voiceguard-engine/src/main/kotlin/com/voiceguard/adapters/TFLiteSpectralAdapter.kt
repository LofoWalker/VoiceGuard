package com.voiceguard.adapters

import com.voiceguard.domain.model.AudioChunk
import com.voiceguard.domain.port.SpectralClassifierPort

/**
 * Production adapter for on-device spectral inference using TensorFlow Lite.
 *
 * **Hardware acceleration policy (ADR-01 / PRD §4.3):**
 * This adapter targets the Tensor G4 NPU on Pixel 9a via `NnApiDelegate` or `GpuDelegate`.
 * Silent CPU fallback is prohibited in production — if hardware acceleration is unavailable
 * the constructor throws [IllegalStateException] so the caller can surface the unsupported
 * state rather than silently running at unacceptable battery cost.
 *
 * **Hexagonal boundary:** Hardware-specific TFLite wiring lives exclusively in this class.
 * [com.voiceguard.rules.SpectralArtifactsRule] depends only on [SpectralClassifierPort] and
 * has zero knowledge of this adapter or TFLite (ADR-01).
 *
 * **Phase 1 status:** End-to-end TFLite inference validation is Phase 2 hardware work.
 * This class establishes the adapter wiring, the hardware-requirement policy, and the
 * [SpectralClassifierPort] implementation contract. The [classify] body is a placeholder
 * returning a neutral score until the `.tflite` model is integrated in Phase 2.
 *
 * To use in production (Phase 2):
 * 1. Add `org.tensorflow:tensorflow-lite` and the NNAPI/GPU delegate to the Android module.
 * 2. Replace the placeholder in [classify] with actual `Interpreter.run()` call.
 * 3. Load the model buffer via `FileUtil.loadMappedFile(context, "spectral_model.tflite")`.
 *
 * @param hardwareAccelerationAvailable Overrides the runtime detection for testing purposes.
 *   In production the default [detectHardwareAcceleration] value is used automatically.
 */
class TFLiteSpectralAdapter(
    hardwareAccelerationAvailable: Boolean = detectHardwareAcceleration()
) : SpectralClassifierPort {

    init {
        // Never degrade to CPU silently — surface the unavailability so the caller can
        // decide whether to fall back to a reduced feature set or surface a user warning.
        if (!hardwareAccelerationAvailable) {
            throw IllegalStateException(
                "Hardware acceleration (NNAPI/GPU delegate) is not available on this device. " +
                        "TFLiteSpectralAdapter requires NPU or GPU delegate for production inference. " +
                        "Enable hardware acceleration or use a different adapter."
            )
        }
    }

    /**
     * Classifies the chunk's spectral signature against vocoder artifact patterns.
     *
     * Phase 1 placeholder — returns a neutral 0.5 score.
     * Phase 2: replace with TFLite Interpreter.run(inputBuffer, outputBuffer).
     */
    override fun classify(chunk: AudioChunk): Float {
        // TODO Phase 2: run TFLite model with NnApiDelegate on the PCM input buffer
        return NEUTRAL_SCORE
    }

    companion object {
        private const val NEUTRAL_SCORE = 0.5f

        /**
         * Detects NNAPI/GPU delegate availability at runtime.
         *
         * In a pure JVM environment Android-specific delegates are absent; returns false.
         * Phase 2 (Android module): replace with actual NnApiDelegate availability check via
         * `NnApiDelegate.Options` probing or `CompatibilityList` from tensorflow-lite-support.
         */
        fun detectHardwareAcceleration(): Boolean = false
    }
}

