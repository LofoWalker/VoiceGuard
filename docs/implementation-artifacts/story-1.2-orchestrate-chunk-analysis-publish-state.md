# Story 1.2: Orchestrate Chunk Analysis and Publish Live Detection State

Status: review

## Story

As an engine integrator,
I want the engine to accept audio chunks and publish live detection state updates,
so that downstream consumers can react to confidence and AI probability in real time.

## Acceptance Criteria

1. The orchestrator processes submitted `AudioChunk` inputs and publishes an updated `DetectionUiState` through a `StateFlow`.
2. During the 0–1 second warm-up period, `globalConfidence` remains forced to `0.0` and no unreliable early verdict is exposed.
3. `elapsedSeconds` advances consistently with processed chunks and the published state remains suitable for real-time consumers.

## Tasks / Subtasks

- [x] Create `domain/service/DetectionOrchestrator.kt` — accepts chunks, drives rule evaluation, emits `StateFlow<DetectionUiState>` (AC: 1)
- [x] Implement the warm-up gate: force `globalConfidence = 0.0f` for the first second of elapsed time (AC: 2)
- [x] Implement `elapsedSeconds` tracking based on chunk cadence (AC: 3)
- [x] Wire `StateFlow` emission after each chunk processing cycle (AC: 1)
- [x] Write unit test: submit chunks and assert `DetectionUiState` is emitted via the StateFlow (AC: 1)
- [x] Write unit test: assert `globalConfidence == 0.0f` for chunks within the first second (AC: 2)
- [x] Write unit test: assert `elapsedSeconds` progresses correctly after multiple chunks (AC: 3)

## Dev Notes

- Builds on Story 1.1 domain contracts; no rule implementations yet — the orchestrator can use stub/fake rules.
- Dispatcher must be injected as a constructor parameter (not hardcoded) to allow `UnconfinedTestDispatcher` substitution in tests (ADR-03 + additional requirements).
- The `StateFlow` must be exposed as a read-only `StateFlow<DetectionUiState>` to consumers; the mutable backing field lives inside the orchestrator.
- Warm-up implementation: check `elapsedSeconds < 1.0f` and force-override `globalConfidence` to `0.0f` before emission.
- The orchestrator is the only component that mutates `ConversationContext` (ADR-03).
- Use `kotlinx-coroutines-test` with `UnconfinedTestDispatcher` for deterministic flow testing.

### Project Structure Notes

- New file: `domain/service/DetectionOrchestrator.kt`
- Tests: `test/.../DetectionOrchestratorTest.kt`
- No adapters or real rules required yet; use `FakeAudioDetectionRule` returning fixed `RuleResult`.

### References

- [Source: architecture.md#Orchestration Engine]
- [Source: architecture.md#Confidence Accumulation Model]
- [Source: architecture.md#ADR-03: Rules Are Stateless Per-Chunk Except for Context Injection]
- [Source: prd.md#3.2 Modélisation de la Courbe d'Accumulation Temporelle]
- [Source: prd.md#4.2 L'Orchestrateur Réactif et Formule du Score]

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-5

### Debug Log References
None — all tests green on first full run.

### Completion Notes List
- AC-1: `DetectionOrchestrator.processChunk()` runs all rules in parallel via `async(dispatcher)` within `coroutineScope` and emits the updated `DetectionUiState` to a `MutableStateFlow` after every chunk.
- AC-2: Warm-up gate implemented in `processChunk()`: `if (totalElapsedSeconds < 1.0f) 0.0f else peakConfidence`. Also suppresses `aiProbability` via the `globalConfidence < 0.05f` NaN guard (ADR-04).
- AC-3: `totalElapsedSeconds` is computed from actual chunk data — `chunk.pcmData.size / chunk.sampleRate` — making tracking resolution-independent.
- ADR-02 (monotone confidence): `peakConfidence = maxOf(peakConfidence, rawConfidence)` ensures the gauge never drops.
- ADR-03: Only `DetectionOrchestrator` calls `context.updateCallDuration()`; rules receive context read-only.
- `FakeAudioDetectionRule` stub used in all tests — no real rule implementations required (as per Story 1.2 scope).

### File List
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/service/DetectionOrchestrator.kt` (new)
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/service/DetectionOrchestratorTest.kt` (new)
- `docs/implementation-artifacts/story-1.2-orchestrate-chunk-analysis-publish-state.md` (updated)
