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
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │  AudioSource     │  │  FftSpectral     │  │  TestHarness   │  │
│  │  (mic / dataset) │  │  Classifier (R-02)│  │  (Gradle task) │  │
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
voiceguard-engine/src/main/kotlin/com/voiceguard/
├── domain/
│   ├── model/
│   │   ├── AudioChunk.kt           # Raw PCM payload value object
│   │   ├── RuleResult.kt           # Score + confidence pair
│   │   └── DetectionUiState.kt     # Aggregated state emitted to consumers
│   ├── port/
│   │   ├── AudioDetectionRule.kt   # Primary port — all rules implement this
│   │   ├── AudioSourcePort.kt      # Secondary port — audio stream provider
│   │   └── SpectralClassifierPort.kt  # Secondary port — per-chunk spectral score
│   ├── service/
│   │   ├── DetectionOrchestrator.kt
│   │   ├── ScoreAggregator.kt
│   │   └── AudioDsp.kt             # Shared DSP utilities (computeRms)
│   └── context/
│       └── ConversationContext.kt  # Speech-switch timestamps, history
├── rules/
│   ├── LatencyBehaviorRule.kt      # R-01
│   ├── SpectralArtifactsRule.kt    # R-02 (delegates to SpectralClassifierPort)
│   └── NoiseLinearityRule.kt       # R-03
├── adapters/
│   ├── FftSpectralClassifier.kt    # DSP/FFT implementation of SpectralClassifierPort
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
| R-02 | `SpectralArtifactsRule` | 0.35 | Incremental from chunk 1 | DSP (FFT, JVM) |
| R-03 | `NoiseLinearityRule` | 0.25 | Max confidence by 2 s | DSP |

### R-01 — LatencyBehaviorRule (weight 0.40)

Detects the fixed-delay signature of a cloud STT → LLM → TTS pipeline. A human's reaction time
is biologically stochastic (typically 180–350 ms, high variance). An AI agent round-trip to a
cloud API produces a suspiciously narrow, repeatable latency window (≈ 1.5–2.2 s).

The rule returns `confidence = 0.0` until the first speech switch is detected, which causes it
to be excluded from the weighted formula during the warm-up phase.

### R-02 — SpectralArtifactsRule (weight 0.35)

TTS vocoders (ElevenLabs, OpenAI TTS, Kokoro) impose a characteristic spectral signature:
a hard band-limiting filter (typically 6–12 kHz) and an unusually uniform mid-band energy
profile. `FftSpectralClassifier` detects these using a pure-JVM Cooley-Tukey FFT (4096-point,
Hann window) and two heuristics:

1. **Band-limiting score** — ratio of high-frequency (> 6 kHz) to mid-frequency (1–4 kHz)
   energy. Natural speech fills the high band; TTS pipelines attenuate it sharply.
2. **Spectral flatness (Wiener entropy)** — measures how tonally uniform the spectrum is.
   An unusually flat mid-band profile suggests vocoder background fill.

The classifier is injected via `SpectralClassifierPort` so it can be replaced by a trained
ML model (Phase 2) without touching the rule or the domain layer.

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
| Initial acoustics | 1–2 s | ~20 % (indicative) | R-03 (max confidence) |
| Stabilization | 3–5 s | ~70 % (indicative) | R-02 incremental + R-03 |
| Behavioral verdict | First switch+ | ≥ 90 % (indicative) | R-01 + R-02 + R-03 |

> **Note:** confidence percentages are indicative; actual values depend on the audio signal
> and will be re-baselined once a real dataset is evaluated.

The UI should display `aiProbability` with a visual muted state when `globalConfidence < 0.6`
to prevent the user from acting on an unreliable early reading.

---

## Performance Architecture

Three mechanisms ensure the engine consumes negligible CPU/battery on a Pixel 9a (Tensor G4,
5100 mAh battery):

### Early Exit

After scoring each chunk, the orchestrator evaluates a lightweight "human confirmed" condition.
If R-03 returns `suspicionScore < 0.05` with `confidence = 1.0` (organic, non-linear noise
confirmed), the orchestrator skips dispatching R-02 (`FftSpectralClassifier`) for that chunk.
The FFT computation is the costliest per-chunk operation, so this short-circuit is the primary
CPU saving for calls with clearly organic audio.

### FFT-Based Spectral Classification (Phase 1)

`FftSpectralClassifier` runs a Cooley-Tukey radix-2 DIT FFT (O(N log N), N=4096) on each
500 ms chunk with a Hann window to reduce spectral leakage. All arithmetic is JVM floating
point — no native library required. Phase 2 will replace this with a trained TFLite model
delegated to the NPU via `NnApiDelegate`, plugged in through the same `SpectralClassifierPort`
seam without modifying any rule or orchestrator code.

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
| DSP | Kotlin + JVM math (FFT) | Pure-JVM Cooley-Tukey FFT; Phase 2 replaces with TFLite via SpectralClassifierPort |
| ML Inference | TensorFlow Lite (Phase 2) | NPU delegation via NnApiDelegate; not yet integrated |
| Testing | JUnit 5 + kotlinx-coroutines-test | `UnconfinedTestDispatcher` for deterministic flow testing |
| Datasets | FoR-rerec + HuggingFace Deepfake | Open-source, telephone-channel degraded samples |

---

## Key Architecture Decisions

### ADR-01: Pure-JVM Domain in Phase 1

**Decision:** No Android SDK import in `domain/` or `rules/` modules. R-02 is implemented
as a pure-JVM FFT classifier (`FftSpectralClassifier`) rather than a TFLite stub.
**Rationale:** Enables fast JVM unit tests on CI without an emulator. The spectral classifier
is injected via `SpectralClassifierPort` and can be swapped for a `FakeSpectralClassifier`
in tests, or for a TFLite model in Phase 2, without touching domain code.
**Trade-off:** The DSP heuristics catch coarse vocoder artifacts (hard band-limiting, flat
mid-band) but will not reliably detect high-quality modern TTS — KPIs must be re-baselined
once a real dataset is evaluated.

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
**Important:** Although rules are stateless *per-chunk* (they do not write to shared state
during analysis), some rules (`NoiseLinearityRule`, `SpectralArtifactsRule`) maintain
**per-stream** mutable state (ramp counters, previous amplitude profile) to accumulate
confidence across chunks. This state is valid only within a single audio stream — each new
file or call must receive a fresh rule instance. The `ValidationRunner` enforces this by
calling `buildProductionRules()` inside the `processorFactory` lambda, so every file gets
independent rule instances with clean state.

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

