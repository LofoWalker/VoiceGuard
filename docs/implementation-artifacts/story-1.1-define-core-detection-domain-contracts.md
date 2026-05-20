# Story 1.1: Define the Core Detection Domain Contracts

Status: ready-for-dev

## Story

As a research engineer,
I want a pure Kotlin JVM domain model and port set for audio detection,
so that the engine can evolve and be tested independently from Android and infrastructure bindings.

## Acceptance Criteria

1. The project includes `AudioChunk`, `RuleResult`, `DetectionUiState`, `ConversationContext`, `AudioDetectionRule`, and `AudioSourcePort` in a domain-centered structure with no Android SDK dependency.
2. `ConversationContext` supports storing conversation timing and history needed for latency analysis with its mutation boundary restricted to the orchestrator.
3. `AudioDetectionRule` exposes a stable chunk-based analysis contract compatible with coroutine-based orchestration.

## Tasks / Subtasks

- [ ] Create `domain/model/AudioChunk.kt` — immutable PCM payload value object, sampleRate defaults to 16 kHz (AC: 1)
- [ ] Create `domain/model/RuleResult.kt` — suspicion score + confidence pair; confidence 0.0 means "no evidence yet" (AC: 1)
- [ ] Create `domain/model/DetectionUiState.kt` — accumulated state emitted to consumers via StateFlow (AC: 1)
- [ ] Create `domain/context/ConversationContext.kt` — thread-safe mutable store for speech-switch timestamps; mutation restricted to orchestrator (AC: 2)
- [ ] Create `domain/port/AudioDetectionRule.kt` — suspend fun interface with name, weight, and analyze contract (AC: 3)
- [ ] Create `domain/port/AudioSourcePort.kt` — secondary port abstraction for audio chunk stream provider (AC: 1)
- [ ] Write unit tests verifying no Android SDK imports appear in domain package (AC: 1)
- [ ] Write unit test asserting `AudioDetectionRule` contract is compatible with coroutine-based call sites (AC: 3)

## Dev Notes

- This story is purely additive: no orchestration logic yet, contracts only.
- All types in `domain/` must have zero dependency on Android framework classes or native libraries (ADR-01).
- `ConversationContext` must be documented clearly: only the orchestrator writes to it; all other components read it.
- Use Kotlin `data class` for value objects (`AudioChunk`, `RuleResult`, `DetectionUiState`).
- `AudioChunk.pcmData: FloatArray` — be mindful that FloatArray equality is reference-based; override equals/hashCode or document accordingly.
- `AudioDetectionRule.weight: Float` — range [0.0, 1.0], used in weighted scoring formula.

### Project Structure Notes

- Target package layout: `voiceguard-engine/domain/model/`, `domain/port/`, `domain/context/`
- No `adapters/` or `rules/` code in this story.
- Build system: Kotlin 2.x, Gradle Kotlin DSL.

### References

- [Source: architecture.md#Domain Model]
- [Source: architecture.md#Package Structure]
- [Source: architecture.md#ADR-01: Pure-JVM Domain in Phase 1]
- [Source: architecture.md#ADR-03: Rules Are Stateless Per-Chunk Except for Context Injection]
- [Source: prd.md#4.1 Modèle de Données & Ports du Domaine]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

