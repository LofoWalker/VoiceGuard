# CLAUDE.md

Guidance for AI assistants (and humans) working in this repository. Explains the
**why** behind the design and the **how** of building, testing, and extending the engine.

> Companion docs: `prd.md` (product spec, FR), `architecture.md` (design rationale & ADRs).
> Those two are the source of intent; this file is the operational map of the code as it
> actually stands today. Where they disagree with the code, the code wins — update the docs.

---

## What this project is

**VoiceGuard-Engine** is a pure-Kotlin/JVM detection core that estimates, in real time,
whether a phone caller is a **human** or an **AI-generated voice** (anti-*vishing*). It
analyses the audio signal itself — not phone-number reputation — so it survives spoofed and
disposable numbers.

The engine is **informative only**: it computes scores and never blocks, drops, or interrupts
a call. This is a hard product constraint (PRD §3.3) and an architectural invariant enforced by
tests — see `DetectionUiState` and `DomainScopeComplianceTest`.

It exposes two live signals, updated every 500 ms chunk:

- **`globalConfidence`** — how mature/reliable the analysis is. Monotonically non-decreasing
  (a "ratchet"). Communicates *evidence accumulated*, not instantaneous certainty.
- **`aiProbability`** — the verdict. Only meaningful once `globalConfidence` is high.

The split exists to avoid the "jauge folle" (a verdict gauge that flickers 0→100% on every
line crackle). Confidence gates the verdict.

### Status vs. the docs

The README/PRD/architecture were written **spec-first** and describe **3 pilot rules**. The
code has since grown to **15 rule classes, 14 wired into production**. When the prose and the
code disagree, trust `ValidationRunner.buildProductionRules()` and `RuleWeightConfig` — they
are the live source of truth for which rules run and at what weight.

---

## Architecture: Hexagonal (Ports & Adapters)

The non-negotiable rule: **the `domain/` and `rules/` layers must stay pure Kotlin/JVM** — zero
Android SDK, zero telephony/call-control APIs, zero native libraries. This keeps the engine
unit-testable on plain JVM CI (no emulator) and lets hardware-specific bindings be injected
later for the Android phase. This is **ADR-01**, and it is enforced at runtime by
`DomainScopeComplianceTest` (it fails CI the moment an `android.*` class becomes reachable from
the domain classpath, or `DetectionUiState` grows a control/action field).

### Package map

```
voiceguard-engine/src/main/kotlin/com/voiceguard/
├── domain/                      # PURE. No infra, no Android, no I/O.
│   ├── model/                   # AudioChunk, RuleResult, DetectionUiState, RuleDiagnostic
│   ├── port/                    # AudioDetectionRule (primary), AudioSourcePort,
│   │                            #   SpectralClassifierPort (secondary ports)
│   ├── service/                 # DetectionOrchestrator, ScoreAggregator, AudioDsp
│   ├── context/                 # ConversationContext (speech-switch timestamps, history)
│   └── dsp/                     # Fft, PitchAnalysis (shared signal-processing primitives)
├── rules/                       # 15 AudioDetectionRule implementations (see table below)
├── adapters/                    # FftSpectralClassifier (impl of SpectralClassifierPort),
│   │                            #   DatasetAudioSource (file-based AudioSourcePort)
└── harness/                     # Off-domain test/validation tooling (Gradle entry points)
    ├── ValidationRunner         # streams a dataset → engine → accuracy/FPR/latency report
    ├── WeightSweepRunner        # random weight-space search to recalibrate weights
    ├── RuleWeightConfig         # SINGLE SOURCE OF TRUTH for production rule weights
    ├── ChunkProcessor           # seam that makes ValidationRunner unit-testable
    └── RuleDiscrimination       # per-rule AUC / Cohen's d diagnostics in the report
```

Dependency direction is strictly inward: `harness` → `adapters` → `domain`. Rules depend only
on `domain`. Adapters implement domain ports.

---

## How a chunk flows through the engine

`DetectionOrchestrator.processChunk(chunk)` is the heart. Per 500 ms chunk:

1. Compute RMS; run `detectTransition()` (speech-turn switch, energy spike, silence-boundary
   crossing) and update the 2 s sliding RMS window.
2. **Phase 1** — `isAlwaysActive` rules run in parallel (`async`/`awaitAll`). Today that's
   `NoiseLinearityRule` (R-03).
3. **Early-exit** — if an `isEarlyExitTrigger` rule reports strong organic evidence
   (`suspicionScore ≤ 0.05` and `confidence == 1.0`), rules marked `canSkipOnEarlyExit` (the
   costly spectral FFT) are skipped for this chunk. CPU/battery optimisation.
4. **Intermittent sampling** — during a stable monologue (RMS variance below threshold, no
   transition), all `isHeavyAnalysis` rules are suspended until the next transition.
5. **Phase 2** — the remaining (non-always-active, non-suppressed) rules run in parallel.
6. **Context mutation happens only after `awaitAll`** (ADR-03) so every rule observes a
   consistent, read-only snapshot of `ConversationContext` during its analysis window.
7. `ScoreAggregator` computes confidence + AI probability; the monotone ratchet and the warm-up
   gate are applied; a new `DetectionUiState` is emitted on the `StateFlow`.

Concurrency contract: `processChunk` is serialised by an internal `Mutex`; rules run in parallel
but **must never mutate `ConversationContext`** and must be coroutine-safe (ADR-03).

### Scoring formula (`ScoreAggregator`)

```
aiProbability = Σ(score_i · weight_i · confidence_i) / Σ(weight_i · confidence_i)
```

- A rule with `confidence == 0.0` self-excludes (contributes 0 to both sums) — this is how a
  rule says "I don't have enough data yet, don't count me." `LatencyBehaviorRule` does this
  until the first speech switch, for example.
- **NaN guard (ADR-04):** when the denominator is 0 (all rules silent during warm-up), the
  result is forced to `0.0f`, never `Float.NaN`.
- `globalConfidence` uses a *different* denominator: the **sum of ALL configured rule weights**,
  so a skipped heavy rule *lowers* confidence rather than inflating it.
- Warm-up gate: for the first 1 s, `globalConfidence` is forced to `0.0`; while it's `< 0.05`,
  `aiProbability` is forced to `0.0` too.

---

## The detection rules

Each rule implements `AudioDetectionRule { name; weight; analyze(chunk, context): RuleResult }`
plus optional dispatch-hint flags (`isAlwaysActive`, `isEarlyExitTrigger`, `isHeavyAnalysis`,
`canSkipOnEarlyExit`). `RuleResult(suspicionScore, confidence)` — both in `[0,1]`;
`confidence = 0` means abstain.

**Production set (14 rules)** wired in `ValidationRunner.buildProductionRules()` with weights
from `RuleWeightConfig.PRODUCTION`. Weights were calibrated on the FoR testing split using
measured **AUC** and **Cohen's d** (see `RuleWeightConfig` companion comments for the full
rationale). They do **not** need to sum to 1 — the aggregator normalises.

| Rule | Weight | Notes (calibrated on FoR testing split) |
|------|-------:|------|
| `SpeechEntropyRule` | 0.85 | Strongest signal (d≈1.65). |
| `ProsodicDynamicsRule` | 0.75 | Strong (d≈1.33). **Direction inverted** — on FoR, real voices are flat read speech; modern TTS is more dynamic. |
| `NoiseLinearityRule` (R-03) | 0.65 | Strong (d≈1.13). `isAlwaysActive` + `isEarlyExitTrigger`. |
| `SpectralArtifactsRule` (R-02) | 0.55 | DSP via `FftSpectralClassifier`. **`invertScore = true`** — FoR fakes are bright, not band-limited. `isHeavyAnalysis` + `canSkipOnEarlyExit`. |
| `HumanImperfectionRule` | 0.50 | Moderate (d≈0.69). |
| `RoomResponseRule` | 0.45 | Moderate (d≈0.62). |
| `CodecArtifactRule` | 0.40 | Moderate (d≈0.51). |
| `JitterShimmerRule` | 0.25 | Weak (d≈0.31). |
| `CepstralPeakRule` | 0.20 | Weak (d≈0.37). |
| `MicroPauseDistributionRule` | 0.05 | **Direction inverted**; saturates on FoR. |
| `EmotionalVarianceRule` | 0.05 | Non-discriminant on FoR (d≈−0.04, noise). |
| `TurnTakingLatencyVarianceRule` | 0.05 | Abstains on FoR (no turn-taking in isolated utterances). |
| `HarmonicConsistencyRule` | 0.05 | Score≈0 for both classes — no signal on FoR. |
| `EnergyEnvelopeRule` | 0.05 | Score≈0 for both classes — no signal on FoR. |

**Not in production:** `LatencyBehaviorRule` (R-01) — measures inter-turn reaction latency, but
isolated-utterance datasets like FoR have no turn-taking, so it would only dilute the weights.
Kept for the future live-call/conversational scenario.

> **Key insight on "inverted" rules:** several rules' raw direction was *backwards* on the FoR
> dataset (e.g. the "fake = band-limited" assumption is false for FoR's bright re-recorded TTS).
> Rather than rewrite the DSP, the wiring flips the score with a constructor flag
> (`invertScore` / `invertDirection`). If you change datasets, **re-verify every direction** —
> these flags are dataset-dependent, not universal truths.

---

## Build, test, validate

JVM toolchain 21, Kotlin 2.0.21, Gradle Kotlin DSL. Single module: `:voiceguard-engine`.

```bash
# Build + unit tests (40 test files; JUnit 5 + kotlinx-coroutines-test)
./gradlew build
./gradlew :voiceguard-engine:test

# Validate against a real dataset → prints accuracy / FPR / latency + per-rule AUC report
./gradlew :voiceguard-engine:validateEngine -PdatasetPath=/path/to/dataset
./gradlew :voiceguard-engine:validateEngine -PdatasetPath=/path/to/dataset -Pverbose

# Recalibrate weights by random search over weight-space
./gradlew :voiceguard-engine:sweepWeights -PdatasetPath=/path/to/dataset \
    -PnRuns=200 -Pstep=0.05 -Pobjective=FPR_CONSTRAINED_RECALL -Pseed=42
```

On Windows use `./gradlew.bat` (or `gradlew.bat` from PowerShell).

**Dataset layout:** the root must contain `real/` (or `human/`) and `fake/` (or `ai`/`deepfake`)
subdirectories of 16-bit mono WAV/MP3 files. MP3 is decoded transparently via the `mp3spi` SPI.
Files are resampled to a common 16 kHz to remove a sample-rate/label confound (real 16 kHz vs
fake 24 kHz would otherwise leak the label).

### Per-stream state — read this before touching rules or the harness

Some rules (`NoiseLinearityRule`, `SpectralArtifactsRule`, and others) keep **per-stream mutable
state** (ramp counters, previous amplitude profile) to accumulate confidence across chunks. That
state is valid only within a single audio stream. **Each file/call must get a fresh rule
instance.** `ValidationRunner` enforces this by calling `buildProductionRules()` inside its
`processorFactory` lambda, so every file gets clean rule instances. Do not hoist rule
construction out of that lambda (ADR-03).

---

## KPIs and current reality

PRD §6.2 targets: **Accuracy ≥ 85%**, **FPR ≤ 5%**, **latency ≤ 50 ms per 500 ms chunk** on JVM.

The decision threshold is `ValidationRunner.CALIBRATED_AI_THRESHOLD = 0.55` (calibrated via the
threshold sweep printed in the report to satisfy the FPR constraint). On the ~4,600-file FoR
testing split, the engine currently lands around **80% accuracy with FPR well above the 5%
target at the recall-favouring operating point** — i.e. the coarse DSP/heuristic rules do not
yet reliably catch modern high-quality TTS. Closing that gap is the open R&D problem (see
`todo.md`, items VG-026..VG-029: correlation analysis, score calibration, a real phone-call
dataset, and aggregator robustness). When you report results, read the live numbers from the
report — do not quote a fixed figure as if it were settled.

`validateEngine` exits non-zero when measurable KPIs fail, so it doubles as a CI gate.

---

## Working in this repo — conventions

- **Keep the domain pure.** No `android.*`, no I/O, no `System.currentTimeMillis()` inside rules
  (use the audio-timeline timestamps in `ConversationContext`). `DomainScopeComplianceTest` will
  catch violations — run it.
- **Weights live in exactly one place:** `RuleWeightConfig`. Never hard-code a weight in a rule
  or in `buildProductionRules`. To change weights, edit the constants (and document the AUC/d
  evidence in the companion comment), or run `sweepWeights` and paste its recommended snippet.
- **New rule checklist:** implement `AudioDetectionRule` in `rules/`; add a weight field to
  `RuleWeightConfig` with a `DEFAULT_*` constant; wire it into `buildProductionRules()`; add a
  unit test mirroring the existing `*RuleTest` pattern; run `validateEngine` to read its AUC/d
  and decide direction + weight. New rules should self-exclude (`confidence = 0`) when they lack
  data rather than emitting a noisy guess.
- **Tests are JUnit 5.** Use `UnconfinedTestDispatcher` for deterministic coroutine/flow tests
  (the orchestrator takes an injectable dispatcher precisely for this).
- **Comments and docs in this repo mix French and English** — match the surrounding file.
- `result.txt` / `result_fresh.txt` are captured validation-run outputs, not source.

---

## ADR quick reference (full text in `architecture.md`)

- **ADR-01** — Domain stays pure JVM in Phase 1; spectral analysis is a real FFT classifier
  behind `SpectralClassifierPort`, swappable for TFLite in Phase 2 without touching domain code.
- **ADR-02** — `globalConfidence` is a one-way ratchet; it never decreases.
- **ADR-03** — Rules are read-only w.r.t. `ConversationContext`; only the orchestrator mutates
  it, and only after `awaitAll`. Per-stream rule state requires fresh instances per file/call.
- **ADR-04** — Zero-denominator scoring yields `0.0f`, never `NaN`.