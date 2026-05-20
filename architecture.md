## VoiceGuard-Engine — Technical Architecture

**Version:** 1.0.0
**Date:** 2026-05-20
**Status:** Draft — Aligned with PRD v1.1.0
**Author:** Winston (Architect)

---

## System Overview

VoiceGuard-Engine is a real-time, on-device AI voice detection core written in **Kotlin (JVM)**,
designed to identify AI-generated voice calls (vishing attacks) by analyzing an audio stream
in flight. The engine is built as a pure domain library — no UI, no Android SDK dependencies in
Phase 1 — so it can be unit-tested on the JVM and later embedded into an Android application.

The design deliberately avoids any external cloud inference call. All analysis is local, which
is both a privacy constraint and a strict latency requirement (≤ 50 ms per 500 ms chunk).

---

## Architecture Style: Hexagonal (Ports & Adapters)

The codebase enforces a strict layering discipline so that the domain is never polluted by
infrastructure concerns (audio drivers, TFLite bindings, Android APIs).

```
┌────────────────────────────────────────────────────────────────┐
│                        Adapters (Infrastructure)               │
│  ┌──────────────────┐  ┌────────────────┐  ┌────────────────┐  │
│  │  AudioSource     │  │  TFLiteAdapter │  │  TestHarness   │  │
│  │  (mic / dataset) │  │  (SpectralRule)│  │  (Gradle task) │  │
│  └────────┬─────────┘  └───────┬────────┘  └───────┬────────┘  │
│           │                   │                    │           │
│  ─ ─ ─ ─ ─│─ ─ ─ ─ ─ ─ ─ ─ ─│─ ─ ─ ─ ─ ─ ─ ─ ─│─ ─ ─ ─ ─  │
│                         DOMAIN BOUNDARY                        │
│  ─ ─ ─ ─ ─│─ ─ ─ ─ ─ ─ ─ ─ ─│─ ─ ─ ─ ─ ─ ─ ─ ─│─ ─ ─ ─ ─  │
│           ▼                   ▼                    ▼           │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                   Domain (Core Engine)                    │ │
│  │  AudioChunk · ConversationContext · AudioDetectionRule    │ │
│  │  DetectionOrchestrator · ScoreAggregator                  │ │
│  └───────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

**Key constraint:** The `domain` module has zero dependencies on any Android framework class or
native library. All hardware-specific bindings live in adapter modules loaded at runtime via
constructor injection.

---

## Package Structure

```
voiceguard-engine/
├── domain/
│   ├── model/
│   │   ├── AudioChunk.kt           # Raw PCM payload value object
│   │   ├── RuleResult.kt           # Score + confidence pair
│   │   └── DetectionUiState.kt     # Aggregated state emitted to consumers
│   ├── port/
│   │   ├── AudioDetectionRule.kt   # Primary port — all rules implement this
│   │   └── AudioSourcePort.kt      # Secondary port — audio stream provider
│   ├── service/
│   │   ├── DetectionOrchestrator.kt
│   │   └── ScoreAggregator.kt
│   └── context/
│       └── ConversationContext.kt  # Speech-switch timestamps, history
├── rules/
│   ├── LatencyBehaviorRule.kt      # R-01
│   ├── SpectralArtifactsRule.kt    # R-02
│   └── NoiseLinearityRule.kt       # R-03
├── adapters/
│   ├── TFLiteSpectralAdapter.kt    # TFLite inference wrapper for R-02
│   └── DatasetAudioSource.kt       # File-based AudioSource for test harness
└── harness/
    └── ValidationRunner.kt         # Gradle-triggered batch scorer
```

---

## Domain Model

```kotlin
// Immutable PCM payload. sampleRate defaults to 16 kHz (telephony standard).
data class AudioChunk(val pcmData: FloatArray, val sampleRate: Int = 16_000)

// Pair emitted by every rule after analyzing one chunk.
// confidence = 0.0 means "I have no evidence yet — exclude me from scoring."
data class RuleResult(val suspicionScore: Float, val confidence: Float)

// Accumulated state exposed to the UI layer via StateFlow.
data class DetectionUiState(
    val globalConfidence: Float,  // Monotonically increasing [0.0, 1.0]
    val aiProbability: Float,     // Dynamic verdict, only reliable when confidence is high
    val elapsedSeconds: Float
)
```

The `ConversationContext` is a mutable, thread-safe object managed exclusively by the
orchestrator. It records speech-activity timestamps so `LatencyBehaviorRule` can measure
reaction delay without coupling itself to system time.

---

## Detection Rules

| ID | Class | Weight | Activation Delay | Signal Type |
|----|-------|--------|-----------------|-------------|
| R-01 | `LatencyBehaviorRule` | 0.40 | After first speech switch | Behavioral |
| R-02 | `SpectralArtifactsRule` | 0.35 | Incremental from chunk 1 | TFLite (TPU) |
| R-03 | `NoiseLinearityRule` | 0.25 | Max confidence by 2 s | DSP |

### R-01 — LatencyBehaviorRule (weight 0.40)

Detects the fixed-delay signature of a cloud STT → LLM → TTS pipeline. A human's reaction time
is biologically stochastic (typically 180–350 ms, high variance). An AI agent round-trip to a
cloud API produces a suspiciously narrow, repeatable latency window (≈ 1.5–2.2 s).

The rule returns `confidence = 0.0` until the first speech switch is detected, which causes it
to be excluded from the weighted formula during the warm-up phase.

### R-02 — SpectralArtifactsRule (weight 0.35)

TTS vocoders (ElevenLabs, OpenAI TTS, Kokoro) leave geometric phase artifacts in the frequency
domain — quantization scars, band-limited harmonics, periodic spectral tiling. A small TFLite
model, executing on the Tensor G4 NPU, classifies each 500 ms chunk and emits an incremental
suspicion score. Confidence grows chunk-by-chunk as the model stabilizes.

### R-03 — NoiseLinearityRule (weight 0.25)

Real acoustic environments produce non-linear, non-repeating noise floors (Brownian motion,
HVAC, typing). A perfect digital silence or a looping noise texture strongly suggests an
artificially injected audio signal. This rule reaches maximum confidence within the first 2 s
because silence analysis requires minimal data.

---

## Orchestration Engine

```
┌────────────────────────────┐
│   Audio Source (Stream)    │
│  (mic / dataset replay)    │
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│     Audio Chunker          │
│ (500ms PCM segmentation)   │
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│     Orchestrator           │
│ (Coroutine Scope + Flow)   │
└─────────────┬──────────────┘
              │
┌─────────────┼─────────────┐
▼             ▼             ▼
┌───────────┐ ┌───────────┐ ┌───────────┐
│ R-01      │ │ R-02      │ │ R-03      │
│ Latency   │ │ Spectral  │ │ Noise     │
└─────┬─────┘ └─────┬─────┘ └─────┬─────┘
      └─────────────┼─────────────┘
                    ▼
     ┌────────────────────────────┐
     │     Score Aggregator       │
     │ (weighted reduction)       │
     └─────────────┬──────────────┘
                   ▼
     ┌────────────────────────────┐
     │   Detection StateFlow      │
     │ (DetectionUiState)         │
     └─────────────┬──────────────┘
                   ▼
     ┌────────────────────────────┐
     │   Consumer (UI / logs /    │
     │   test harness / debug)    │
     └────────────────────────────┘
```

Each chunk triggers a `coroutineScope { rules.map { async { it.analyze(chunk, ctx) } }.awaitAll() }`
pattern, running all three rules in parallel within the same dispatcher. The dispatcher is
injected so it can be substituted with `UnconfinedTestDispatcher` in unit tests.

---

## Scoring Formula

The global AI probability is a **dynamic weighted average** that automatically excludes rules
with zero confidence (i.e., rules that do not yet have enough data to vote):

```
Score_global = Σ(Score_i × Weight_i × Confidence_i) / Σ(Weight_i × Confidence_i)
```

This formula has a critical property: during the first 0–1 s warm-up window, if all rules return
`confidence = 0.0`, the denominator is zero and the score is undefined. The orchestrator
explicitly gates `aiProbability` to `0.0f` while `globalConfidence < 0.05f` rather than
propagating a NaN.

---

## Confidence Accumulation Model

The `globalConfidence` gauge is **monotonically non-decreasing** — it never drops. It is
computed from a time-weighted blend of individual rule confidences, capped by a hard warm-up
floor for the first second.

| Phase | Elapsed | globalConfidence | Active Rules |
|-------|---------|-----------------|--------------|
| Warm-up | 0–1 s | forced 0.0 | None (buffers filling) |
| Initial acoustics | 1–2 s | ≈ 20 % | R-03 (max confidence) |
| Stabilization | 3–5 s | ≈ 70 % | R-02 incremental + R-03 |
| Behavioral verdict | First switch+ | ≥ 90 % | R-01 + R-02 + R-03 |

The UI should display `aiProbability` with a visual muted state when `globalConfidence < 0.6`
to prevent the user from acting on an unreliable early reading.

---

## Performance Architecture

Three mechanisms ensure the engine consumes negligible CPU/battery on a Pixel 9a (Tensor G4,
5100 mAh battery):

### Early Exit

After scoring each chunk, the orchestrator evaluates a lightweight "human confirmed" condition.
If R-03 returns `suspicionScore < 0.05` with `confidence = 1.0` (organic, non-linear noise
confirmed), the orchestrator skips dispatching R-02 (the TFLite model) for that chunk. This
short-circuits the most expensive operation when the evidence is already clear.

### TPU Delegation (TFLite)

The spectral classification model is compiled to `.tflite` format and loaded with
`GpuDelegate` / `NnApiDelegate` (targeting the Tensor G4 NPU). The CPU is not involved in
matrix multiplication during inference. R-02 must never fall back to CPU inference in
production — a `NnApiDelegate` availability check gates model initialization and surfaces a
warning if hardware acceleration is unavailable.

### Intermittent Sampling

The audio chunker monitors RMS energy over a sliding 2 s window. During stable monologues
(RMS variance < threshold, no silence boundaries), the orchestrator suspends R-01 and R-02
analysis and only runs R-03 for passive noise monitoring. It resumes full analysis on any
silence event, speech transition, or energy spike. This is the primary battery-saving mechanism
during long uninterrupted speech segments.

---

## Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Language | Kotlin 2.x | Coroutines, value classes, sealed classes |
| Build | Gradle (Kotlin DSL) | Plugin ecosystem for Kotlin multiplatform path |
| Async | Kotlin Coroutines + StateFlow | Reactive backpressure without RxJava overhead |
| DSP | Kotlin + JVM math (FFT) | Pure-JVM for Phase 1; replace with NNAPI in Phase 2 |
| ML Inference | TensorFlow Lite | NPU delegation, minimal binary size |
| Testing | JUnit 5 + kotlinx-coroutines-test | `UnconfinedTestDispatcher` for deterministic flow testing |
| Datasets | FoR-rerec + HuggingFace Deepfake | Open-source, telephone-channel degraded samples |

---

## Key Architecture Decisions

### ADR-01: Pure-JVM Domain in Phase 1

**Decision:** No Android SDK import in `domain/` or `rules/` modules.
**Rationale:** Enables fast JVM unit tests on CI without an emulator. The TFLite adapter for
R-02 is mocked in tests using a `FakeSpectralClassifier` that returns pre-seeded scores.
**Trade-off:** R-02 cannot be validated end-to-end until the Android adapter is wired in Phase 2.

### ADR-02: Monotone Confidence Signal

**Decision:** `globalConfidence` is a one-way ratchet; it never decreases.
**Rationale:** A decreasing confidence gauge would confuse users and create "jauge folle"
oscillation. The gauge communicates evidence accumulation, not instantaneous certainty.
**Trade-off:** A sudden change in call quality (e.g., network routing switch) that would
legitimately reset audio analysis is invisible to the confidence gauge.

### ADR-03: Rules Are Stateless Per-Chunk Except for Context Injection

**Decision:** Rules receive `ConversationContext` by reference but must not mutate it directly.
Only the orchestrator writes to `ConversationContext` (speech switch timestamps, call duration).
**Rationale:** Prevents race conditions when rules execute in parallel coroutines.

### ADR-04: Score NaN Guard

**Decision:** When the weighted denominator equals zero (all-zero confidence during warm-up),
`aiProbability` is hard-coded to `0.0f` rather than propagating a `Float.NaN` downstream.
**Rationale:** NaN propagation into the UI state machine causes undefined rendering behavior.

---

## Validation Strategy

The `ValidationRunner` Gradle task streams audio files from a local dataset directory through
the full engine pipeline (chunker → orchestrator → scorer) and accumulates per-file verdicts.

**Success criteria (PRD § 6.2):**

- Accuracy ≥ 85 % on the mixed test set (FoR-rerec + HuggingFace deepfake)
- False Positive rate ≤ 5 % (human voices misclassified as AI)
- Processing latency ≤ 50 ms per 500 ms chunk on JVM (measured with `System.nanoTime()` guards
  around the orchestrator dispatch loop)

The harness bypasses the `AudioSourcePort` and feeds `AudioChunk` objects directly, giving
deterministic, reproducible test runs with no microphone dependency.

