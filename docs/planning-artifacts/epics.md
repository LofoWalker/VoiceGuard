---
stepsCompleted:
  - step-01-validate-prerequisites
  - step-02-design-epics
  - step-03-create-stories
  - step-04-final-validation
inputDocuments:
  - /home/lofo/Work/VoiceGuard/prd.md
  - /home/lofo/Work/VoiceGuard/architecture.md
---

# VoiceGuard-Engine - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for VoiceGuard-Engine, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories.

## Requirements Inventory

### Functional Requirements

FR1: The system shall process live audio as 500 ms chunks and update detection state twice per second.

FR2: The system shall expose two real-time indicators: `globalConfidence` for analysis maturity and `aiProbability` for the current AI-generated voice verdict.

FR3: The system shall keep `globalConfidence` monotonically non-decreasing from call start until the end of the analysis session.

FR4: The system shall hold `globalConfidence` at 0% during the initial 0–1 second warm-up period to avoid startup false positives.

FR5: The system shall compute `aiProbability` using a dynamic weighted average that excludes rules whose confidence is 0.0.

FR6: The system shall gate `aiProbability` to `0.0f` whenever the overall confidence is below the warm-up reliability threshold so undefined scores are never emitted.

FR7: The system shall orchestrate rule execution asynchronously using Kotlin Coroutines and publish consolidated detection state through `StateFlow`.

FR8: The system shall implement three pilot detection rules in Phase 1: `LatencyBehaviorRule`, `SpectralArtifactsRule`, and `NoiseLinearityRule`.

FR9: The system shall detect suspicious behavioral latency after speech-turn switches by comparing observed response timing against expected human reaction variability.

FR10: The system shall analyze spectral artifacts on each chunk using a lightweight local TensorFlow Lite classifier and accumulate confidence over time.

FR11: The system shall analyze background-noise linearity to identify suspicious digital silence or repeated ambient loops and reach strong confidence within the first 2 seconds.

FR12: The orchestrator shall run detection rules in parallel for each chunk using an injected dispatcher so execution behavior is testable and configurable.

FR13: The system shall maintain a thread-safe `ConversationContext` that stores conversation timing and history required for latency analysis.

FR14: The orchestrator shall be the only component allowed to mutate `ConversationContext`.

FR15: The system shall support early exit behavior that skips expensive spectral inference for a chunk when lightweight evidence strongly confirms a human caller.

FR16: The system shall support intermittent sampling during stable monologues by suspending heavier rules and resuming full analysis on silence boundaries, speech transitions, or energy spikes.

FR17: The system shall provide a dataset-driven validation runner that streams test audio through the full engine pipeline and produces per-file verdicts.

FR18: The validation harness shall allow direct feeding of `AudioChunk` inputs without requiring microphone hardware.

FR19: The engine shall operate as an informative detection core and shall never automatically terminate or interrupt a call.

FR20: The Phase 1 implementation shall remain a pure Kotlin JVM core with no Android SDK dependency in the domain logic.

### NonFunctional Requirements

NFR1: The engine shall perform all inference and analysis locally on-device with no external cloud inference calls.

NFR2: The system shall process each 500 ms audio chunk with a real computation latency of no more than 50 ms on the JVM validation environment.

NFR3: The Phase 1 engine shall achieve at least 85% classification accuracy on the mixed FoR-rerec and Hugging Face deepfake test set.

NFR4: The Phase 1 engine shall maintain a false positive rate of no more than 5% on human voice samples.

NFR5: The architecture shall preserve strict hexagonal separation so domain code is not polluted by infrastructure, Android, or native binding concerns.

NFR6: Domain and rule modules shall remain free of Android framework dependencies to support fast JVM-based unit testing.

NFR7: The system shall preserve privacy by analyzing audio locally and avoiding cloud transfer of call content.

NFR8: The solution shall be optimized for low CPU and battery consumption on mobile-class hardware such as a Pixel 9a.

NFR9: The confidence signal shall remain stable and monotonic to avoid a visually erratic user experience.

NFR10: The system shall be thread-safe when rules execute in parallel on the same chunk.

NFR11: The scoring pipeline shall never emit `NaN` values downstream to consumers.

NFR12: The validation pipeline shall be deterministic and reproducible without microphone dependency.

### Additional Requirements

- Structure the implementation around a hexagonal package layout covering `domain`, `rules`, `adapters`, and `harness` concerns.
- Define the core domain around `AudioChunk`, `RuleResult`, `DetectionUiState`, `ConversationContext`, `AudioDetectionRule`, and audio source abstractions.
- Keep hardware-specific bindings in adapter modules and load them through constructor injection.
- Inject the coroutine dispatcher into the orchestrator so unit tests can substitute `UnconfinedTestDispatcher`.
- Ensure rules are stateless per chunk and must not mutate `ConversationContext` directly.
- Implement weighted score aggregation with exclusion of zero-confidence rules.
- Apply an explicit warm-up guard that emits `aiProbability = 0.0f` while evidence is insufficient.
- Implement an early-exit path that skips `SpectralArtifactsRule` when `NoiseLinearityRule` strongly confirms organic human noise.
- Implement intermittent sampling based on RMS variance and speech/silence transitions to reduce unnecessary work during stable monologues.
- Run `SpectralArtifactsRule` through a TensorFlow Lite adapter capable of hardware acceleration via `NnApiDelegate` or equivalent delegate support.
- Prevent CPU fallback for production spectral inference and surface a warning when hardware acceleration is unavailable.
- Provide a fake spectral classifier or equivalent test double so spectral behavior can be unit-tested without the Android adapter.
- Implement a Gradle-triggered `ValidationRunner` that streams dataset audio through chunking, orchestration, and scoring.
- Measure chunk-processing latency in validation using timing guards around orchestrator dispatch.
- Use Kotlin 2.x, Gradle Kotlin DSL, Kotlin Coroutines, StateFlow, JUnit 5, and `kotlinx-coroutines-test` as the core Phase 1 technology baseline.

### UX Design Requirements

- No dedicated UX design specification was provided for the current Phase 1 scope.
- The only user-facing behavior currently defined is that low-confidence verdicts should be visually muted when `globalConfidence < 0.6`.
- Any concrete UI component, interaction, accessibility, or visual design work remains out of scope for this artifact until a UX specification exists.

### FR Coverage Map

FR1: Epic 1 - Establish the 500 ms chunk-processing pipeline and live state updates.

FR2: Epic 1 - Expose the dual real-time outputs `globalConfidence` and `aiProbability`.

FR3: Epic 1 - Guarantee monotonic confidence progression throughout analysis.

FR4: Epic 1 - Apply the 0–1 second warm-up confidence floor.

FR5: Epic 1 - Compute weighted AI probability while excluding zero-confidence rules.

FR6: Epic 1 - Prevent undefined output by gating low-confidence AI probability to `0.0f`.

FR7: Epic 1 - Orchestrate rule execution with Kotlin Coroutines and publish via `StateFlow`.

FR8: Epic 2 - Deliver the three pilot detection rules for Phase 1.

FR9: Epic 2 - Detect suspicious behavioral latency after speech-turn switches.

FR10: Epic 2 - Analyze spectral artifacts with a local TFLite-based classifier.

FR11: Epic 2 - Detect suspicious background-noise linearity and rapid early evidence.

FR12: Epic 1 - Run per-chunk rule evaluation in parallel with an injected dispatcher.

FR13: Epic 1 - Maintain a thread-safe `ConversationContext` for timing and history.

FR14: Epic 1 - Restrict `ConversationContext` mutation to the orchestrator.

FR15: Epic 2 - Skip expensive spectral inference when lightweight evidence already confirms a human caller.

FR16: Epic 2 - Suspend heavy analysis during stable monologues and resume on meaningful transitions.

FR17: Epic 3 - Provide a dataset-driven validation runner for full-pipeline evaluation.

FR18: Epic 3 - Support direct `AudioChunk` feeding without microphone dependency.

FR19: Epic 1 - Keep the engine informative-only and never auto-terminate calls.

FR20: Epic 1 - Preserve a pure Kotlin JVM Phase 1 implementation with no Android SDK dependency.

## Epic List

### Epic 1: Real-Time Detection Core

Deliver a runnable Kotlin JVM detection core that ingests live audio chunks and emits stable, real-time confidence and AI probability signals for downstream consumers.

**FRs covered:** FR1, FR2, FR3, FR4, FR5, FR6, FR7, FR12, FR13, FR14, FR19, FR20

### Epic 2: AI Voice Signal Analysis and Mobile Efficiency

Deliver the actual AI voice detection capability by combining behavioral latency, spectral artifact analysis, and noise-linearity detection with mobile-aware optimization strategies.

**FRs covered:** FR8, FR9, FR10, FR11, FR15, FR16

### Epic 3: Research Validation and Benchmarking Harness

Deliver a reproducible validation harness that proves the engine’s accuracy, false-positive rate, and latency against target datasets without requiring live hardware capture.

**FRs covered:** FR17, FR18

<!-- Detailed epic and story breakdown -->

## Epic 1: Real-Time Detection Core

Deliver a runnable Kotlin JVM detection core that ingests live audio chunks and emits stable, real-time confidence and AI probability signals for downstream consumers.

### Story 1.1: Define the Core Detection Domain Contracts

As a research engineer,
I want a pure Kotlin JVM domain model and port set for audio detection,
So that the engine can evolve and be tested independently from Android and infrastructure bindings.

**FRs implemented:** FR13, FR14, FR20

**Acceptance Criteria:**

**Given** the Phase 1 engine codebase is being initialized
**When** the domain contracts are defined
**Then** the project includes `AudioChunk`, `RuleResult`, `DetectionUiState`, `ConversationContext`, `AudioDetectionRule`, and `AudioSourcePort` in a domain-centered structure
**And** these contracts have no Android SDK dependency

**Given** `ConversationContext` is part of the domain
**When** its responsibilities are defined
**Then** it supports storing conversation timing and history needed for latency analysis
**And** its mutation boundary is documented for orchestrator-only writes

**Given** detection rules will be added later
**When** `AudioDetectionRule` is defined
**Then** it exposes a stable analysis contract for chunk-based evaluation
**And** the contract is compatible with coroutine-based orchestration

### Story 1.2: Orchestrate Chunk Analysis and Publish Live Detection State

As an engine integrator,
I want the engine to accept audio chunks and publish live detection state updates,
So that downstream consumers can react to confidence and AI probability in real time.

**FRs implemented:** FR1, FR2, FR4, FR7

**Acceptance Criteria:**

**Given** an audio chunk is submitted to the engine
**When** the orchestrator processes it
**Then** the engine evaluates the available rules for that chunk
**And** publishes an updated `DetectionUiState` through `StateFlow`

**Given** the engine is within the first second of analysis
**When** chunks are processed during warm-up
**Then** `globalConfidence` remains forced to `0.0`
**And** no unreliable early verdict is exposed

**Given** multiple chunks are processed in sequence
**When** detection state is updated over time
**Then** `elapsedSeconds` advances consistently with processed chunks
**And** the published state remains suitable for real-time consumers

### Story 1.3: Compute Safe Weighted AI Probability from Rule Results

As a research engineer,
I want the engine to aggregate rule outputs with confidence-aware scoring,
So that the emitted AI probability reflects only the rules that currently have enough evidence.

**FRs implemented:** FR3, FR5, FR6

**Acceptance Criteria:**

**Given** a set of rule results is available for a chunk
**When** the score aggregator computes `aiProbability`
**Then** only rules with non-zero confidence contribute to the weighted result
**And** their configured weights are applied in the formula

**Given** all rules return `confidence = 0.0`
**When** aggregation is attempted during warm-up
**Then** the engine emits `aiProbability = 0.0f`
**And** no `NaN` or undefined numeric value is propagated

**Given** successive chunk results are aggregated
**When** `globalConfidence` is updated
**Then** the confidence signal never decreases
**And** the output remains stable for consumer display logic

### Story 1.4: Execute Rule Analysis in Parallel with a Controlled Context Boundary

As a platform engineer,
I want rule analysis to run in parallel with an injected dispatcher and controlled shared context access,
So that the engine remains testable, concurrent, and free from race-prone rule behavior.

**FRs implemented:** FR12, FR13, FR14

**Acceptance Criteria:**

**Given** multiple detection rules are registered
**When** a chunk is analyzed
**Then** the orchestrator executes rule analysis in parallel for that chunk
**And** the dispatcher is injected rather than hardcoded

**Given** unit or integration tests need deterministic execution
**When** the orchestrator is constructed in tests
**Then** a test dispatcher can be substituted
**And** orchestration behavior remains verifiable without platform dependencies

**Given** rules receive access to `ConversationContext`
**When** they analyze chunks concurrently
**Then** rules can read the context needed for analysis
**And** only the orchestrator is responsible for mutating it

### Story 1.5: Enforce Informative-Only Engine Behavior for Phase 1

As a product team member,
I want the engine to remain strictly advisory in Phase 1,
So that the system informs human judgment without taking irreversible action on live calls.

**FRs implemented:** FR19, FR20

**Acceptance Criteria:**

**Given** the engine emits detection state
**When** downstream consumers integrate with it
**Then** the engine exposes analysis signals only
**And** it does not include any behavior that terminates, blocks, or interrupts calls

**Given** the Phase 1 implementation is reviewed for scope compliance
**When** the architecture and exposed contracts are inspected
**Then** the engine remains a pure Kotlin JVM core
**And** no Android-specific call-control dependency is introduced into the detection domain

## Epic 2: AI Voice Signal Analysis and Mobile Efficiency

Deliver the actual AI voice detection capability by combining behavioral latency, spectral artifact analysis, and noise-linearity detection with mobile-aware optimization strategies.

### Story 2.1: Detect Suspicious Background-Noise Linearity

As a research engineer,
I want the engine to identify digital silence and repeated ambient patterns,
So that it can produce early evidence that a caller may be artificially generated.

**FRs implemented:** FR8, FR11

**Acceptance Criteria:**

**Given** the engine receives early call audio chunks
**When** `NoiseLinearityRule` analyzes background texture
**Then** it detects suspiciously perfect silence and repeated loop-like noise patterns
**And** returns a `RuleResult` with a suspicion score and confidence

**Given** the first 2 seconds of audio are available
**When** `NoiseLinearityRule` has enough evidence
**Then** its confidence reaches its intended high-confidence operating range
**And** the result can contribute to the global score without waiting for speech-turn switches

**Given** real-world noisy audio is analyzed
**When** the background texture is non-linear and organic
**Then** the rule can produce low suspicion outcomes
**And** its behavior supports later early-exit decisions without forcing a false AI verdict

### Story 2.2: Detect Suspicious Conversational Latency After Speech Turns

As a research engineer,
I want the engine to evaluate response timing after a speech-turn switch,
So that it can identify latency patterns consistent with an STT-to-LLM-to-TTS pipeline.

**FRs implemented:** FR8, FR9

**Acceptance Criteria:**

**Given** no speech-turn switch has been observed yet
**When** `LatencyBehaviorRule` is evaluated
**Then** it returns `confidence = 0.0`
**And** it does not influence the weighted AI probability before enough evidence exists

**Given** `ConversationContext` records a speech-turn switch
**When** `LatencyBehaviorRule` evaluates the observed response delay
**Then** it scores narrow, repeated delays in the suspicious AI-response range higher than human-like variable delays
**And** returns a confidence-aware `RuleResult`

**Given** a naturally variable human response pattern
**When** latency measurements are analyzed across turn changes
**Then** the rule avoids classifying normal conversational variance as strongly suspicious
**And** remains compatible with the orchestrator-owned context model

### Story 2.3: Detect Spectral Artifacts with a Local Classifier Abstraction

As a research engineer,
I want the engine to score vocoder-like spectral artifacts through a local classifier interface,
So that synthetic speech signals can be detected incrementally without coupling the domain to Android bindings.

**FRs implemented:** FR8, FR10

**Acceptance Criteria:**

**Given** `SpectralArtifactsRule` is part of the Phase 1 rule set
**When** a chunk is analyzed
**Then** it obtains a classifier result through an adapter-friendly abstraction
**And** converts that result into a suspicion score and confidence contribution

**Given** the engine processes multiple chunks over time
**When** `SpectralArtifactsRule` accumulates evidence
**Then** its confidence grows incrementally as intended
**And** the rule remains usable in orchestrator scoring alongside the other pilot rules

**Given** unit tests need deterministic spectral behavior
**When** the rule is exercised in the JVM test environment
**Then** a fake spectral classifier or equivalent test double can be substituted
**And** no Android or hardware inference dependency is required for rule-level testing

### Story 2.4: Support Hardware-Accelerated Spectral Inference for Production Use

As a platform engineer,
I want the spectral-classification adapter to use hardware-accelerated local inference in production,
So that the engine can run mobile-capable AI audio analysis without unacceptable CPU cost.

**FRs implemented:** FR10

**Acceptance Criteria:**

**Given** the production spectral adapter is initialized
**When** hardware acceleration support is available
**Then** the adapter uses an NNAPI-compatible or equivalent hardware-accelerated delegate path
**And** exposes classifier output to `SpectralArtifactsRule`

**Given** hardware acceleration is unavailable in production conditions
**When** the spectral adapter is created
**Then** the system does not silently fall back to CPU inference for the production path
**And** surfaces the unsupported state clearly for operational handling

**Given** the Phase 1 architecture is reviewed
**When** the spectral adapter is inspected
**Then** hardware-specific logic remains isolated from the domain layer
**And** the domain-facing rule contract stays pure Kotlin/JVM-friendly

### Story 2.5: Reduce Heavy Analysis During Clearly Human or Stable Audio Segments

As a product team member,
I want the engine to reduce unnecessary heavy analysis when human evidence is already strong or speech is stable,
So that detection remains mobile-efficient without losing meaningful responsiveness.

**FRs implemented:** FR15, FR16

**Acceptance Criteria:**

**Given** `NoiseLinearityRule` returns strong organic-human evidence for a chunk
**When** the orchestrator evaluates whether to run expensive inference
**Then** it can skip `SpectralArtifactsRule` for that chunk
**And** the decision follows the defined early-exit policy

**Given** a stable monologue is detected through low RMS variance and no relevant transitions
**When** the chunk-processing strategy is evaluated
**Then** heavier analysis paths can be suspended temporarily
**And** passive monitoring continues through the lighter rule path

**Given** a silence boundary, speech transition, or energy spike occurs
**When** adaptive sampling is reevaluated
**Then** full analysis resumes automatically
**And** the engine returns to normal multi-rule processing without requiring manual intervention

## Epic 3: Research Validation and Benchmarking Harness

Deliver a reproducible validation harness that proves the engine’s accuracy, false-positive rate, and latency against target datasets without requiring live hardware capture.

### Story 3.1: Replay Dataset Audio Through the Engine Without Live Capture

As a research engineer,
I want the validation harness to feed recorded dataset audio directly into the engine pipeline,
So that I can evaluate detection behavior without relying on microphone hardware or live call setup.

**FRs implemented:** FR17, FR18

**Acceptance Criteria:**

**Given** a supported validation dataset is available locally
**When** the validation harness is executed
**Then** it loads audio samples from the dataset source
**And** feeds them into the engine as replayed inputs rather than requiring microphone capture

**Given** the engine pipeline expects chunk-based audio
**When** dataset audio is processed for validation
**Then** the harness produces `AudioChunk` inputs in the expected 500 ms cadence
**And** preserves the evaluation flow used by the orchestrator and rules

**Given** validation is run in a repeatable test environment
**When** the same dataset subset is replayed again
**Then** the harness uses the same deterministic input path
**And** does not depend on external runtime variability from live capture

### Story 3.2: Produce Per-File Verdicts and Aggregate Evaluation Metrics

As a product and research team member,
I want the validation harness to report file-level outcomes and dataset-level metrics,
So that I can judge whether the engine is meeting the Phase 1 detection goals.

**FRs implemented:** FR17

**Acceptance Criteria:**

**Given** validation audio files are processed through the engine
**When** the run completes
**Then** the harness produces a verdict for each file
**And** makes those verdicts available for later inspection or summary

**Given** the validation set includes human and AI-generated samples
**When** results are aggregated
**Then** the harness computes overall accuracy and false positive rate
**And** the reported metrics are sufficient to compare against the Phase 1 KPI targets

**Given** the evaluation output is reviewed after a run
**When** the summary is generated
**Then** it clearly distinguishes aggregate performance from individual sample outcomes
**And** supports identifying where the engine is underperforming

### Story 3.3: Measure Chunk-Processing Latency Against the Real-Time Budget

As a platform engineer,
I want the validation harness to measure processing time around orchestrator execution,
So that I can verify the engine stays within the per-chunk latency budget.

**FRs implemented:** FR17

**Acceptance Criteria:**

**Given** a validation run is executing
**When** each chunk is processed through orchestration and scoring
**Then** the harness measures processing time using timing guards around the dispatch path
**And** records latency data suitable for comparison with the 50 ms target

**Given** latency data has been collected across a run
**When** the run summary is produced
**Then** the harness reports whether the observed processing behavior stays within the expected per-chunk budget
**And** highlights any budget violations clearly

**Given** performance analysis needs to be reproducible
**When** the same validation scenario is repeated in the same environment
**Then** the latency measurement approach remains consistent
**And** avoids dependence on microphone or live-call conditions

### Story 3.4: Run Validation as a Repeatable Gradle-Triggered Workflow

As a development team member,
I want validation to run through a standard project task,
So that the engine can be benchmarked consistently during ongoing implementation.

**FRs implemented:** FR17

**Acceptance Criteria:**

**Given** the project build workflow is available
**When** a developer triggers the validation task
**Then** the validation harness runs through a Gradle-driven entry point
**And** uses the configured dataset and engine pipeline consistently

**Given** the validation workflow is used repeatedly during development
**When** multiple runs are performed over time
**Then** the entry point remains stable and easy to invoke
**And** the workflow supports regression tracking against the same success criteria

**Given** the validation task is reviewed for scope compliance
**When** its inputs and outputs are inspected
**Then** it remains focused on engine evaluation rather than UI or OS integration concerns
**And** it stays compatible with the pure Phase 1 R&D scope

