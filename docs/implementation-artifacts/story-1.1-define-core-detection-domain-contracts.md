# Story 1.1: Define the Core Detection Domain Contracts

Status: review

## Story

As a research engineer,
I want a pure Kotlin JVM domain model and port set for audio detection,
so that the engine can evolve and be tested independently from Android and infrastructure bindings.

## Acceptance Criteria

1. The project includes `AudioChunk`, `RuleResult`, `DetectionUiState`, `ConversationContext`, `AudioDetectionRule`, and `AudioSourcePort` in a domain-centered structure with no Android SDK dependency.
2. `ConversationContext` supports storing conversation timing and history needed for latency analysis with its mutation boundary restricted to the orchestrator.
3. `AudioDetectionRule` exposes a stable chunk-based analysis contract compatible with coroutine-based orchestration.

## Tasks / Subtasks

- [x] Create `domain/model/AudioChunk.kt` — immutable PCM payload value object, sampleRate defaults to 16 kHz (AC: 1)
- [x] Create `domain/model/RuleResult.kt` — suspicion score + confidence pair; confidence 0.0 means "no evidence yet" (AC: 1)
- [x] Create `domain/model/DetectionUiState.kt` — accumulated state emitted to consumers via StateFlow (AC: 1)
- [x] Create `domain/context/ConversationContext.kt` — thread-safe mutable store for speech-switch timestamps; mutation restricted to orchestrator (AC: 2)
- [x] Create `domain/port/AudioDetectionRule.kt` — suspend fun interface with name, weight, and analyze contract (AC: 3)
- [x] Create `domain/port/AudioSourcePort.kt` — secondary port abstraction for audio chunk stream provider (AC: 1)
- [x] Write unit tests verifying no Android SDK imports appear in domain package (AC: 1)
- [x] Write unit test asserting `AudioDetectionRule` contract is compatible with coroutine-based call sites (AC: 3)

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

GitHub Copilot (bmad-agent-dev / Amelia) — 2026-05-20

### Debug Log References

No blockers. Gradle 8.7 bootstrapped via temporary download (no system Gradle available).

### Completion Notes List

- Bootstrapped Gradle 8.7 Kotlin DSL project from scratch (no prior build files existed).
- `AudioChunk`: `FloatArray` equality override via `contentEquals`/`contentHashCode` — standard data-class FloatArray pitfall documented in Dev Notes.
- `ConversationContext`: `CopyOnWriteArrayList` internally; `speechSwitchTimestamps` returns an immutable snapshot via `toList()` to prevent external mutation.
- `AudioDetectionRule`: regular `interface` (not `fun interface`) — `name` and `weight` are abstract properties, preventing ambiguity with SAM conversion.
- All 23 tests pass. ADR-01 (no Android SDK) verified via ClassNotFoundException probe in two separate test suites.
- Branch: `story/1.1-core-detection-domain-contracts`

### File List

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradlew`
- `gradlew.bat`
- `voiceguard-engine/build.gradle.kts`
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/model/AudioChunk.kt`
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/model/RuleResult.kt`
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/model/DetectionUiState.kt`
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/context/ConversationContext.kt`
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/port/AudioDetectionRule.kt`
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/port/AudioSourcePort.kt`
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/model/AudioChunkTest.kt`
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/model/RuleResultTest.kt`
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/model/DetectionUiStateTest.kt`
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/context/ConversationContextTest.kt`
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/port/AudioDetectionRuleTest.kt`
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/port/AudioSourcePortTest.kt`

