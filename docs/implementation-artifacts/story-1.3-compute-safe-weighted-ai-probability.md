# Story 1.3: Compute Safe Weighted AI Probability from Rule Results

Status: review

## Story

As a research engineer,
I want the engine to aggregate rule outputs with confidence-aware scoring,
so that the emitted AI probability reflects only the rules that currently have enough evidence.

## Acceptance Criteria

1. Only rules with non-zero confidence contribute to the weighted `aiProbability` result, with their configured weights applied.
2. When all rules return `confidence = 0.0`, the engine emits `aiProbability = 0.0f` — no `NaN` or undefined value is propagated.
3. `globalConfidence` never decreases across successive chunk aggregations; the signal remains stable.

## Tasks / Subtasks

- [x] Create `domain/service/ScoreAggregator.kt` — implements `Σ(Score_i × Weight_i × Confidence_i) / Σ(Weight_i × Confidence_i)` (AC: 1)
- [x] Implement denominator-zero guard: return `0.0f` for `aiProbability` when all confidences are `0.0` (AC: 2)
- [x] Implement monotonic `globalConfidence` accumulation — value must never decrease (AC: 3)
- [x] Integrate `ScoreAggregator` into `DetectionOrchestrator` to replace any placeholder scoring (AC: 1)
- [x] Write unit test: mixed rules with zero and non-zero confidence — assert only non-zero contribute (AC: 1)
- [x] Write unit test: all-zero confidence scenario — assert `aiProbability == 0.0f`, no NaN emitted (AC: 2)
- [x] Write unit test: multiple aggregation cycles — assert `globalConfidence` is monotonically non-decreasing (AC: 3)

## Dev Notes

- Scoring formula from architecture: `Score_global = Σ(Score_i × Weight_i × Confidence_i) / Σ(Weight_i × Confidence_i)`.
- The denominator-zero guard is ADR-04 — this is a named decision; the guard must not silently let `Float.NaN` propagate to `StateFlow`.
- `globalConfidence` is a one-way ratchet (ADR-02): it only increases or stays the same, never decreases.
- `ScoreAggregator` should be a pure function or a stateless service; state (previous confidence) lives in the orchestrator.
- Consider using `Float.isNaN()` and `Float.isInfinite()` guards as defensive checks.

### Project Structure Notes

- New file: `domain/service/ScoreAggregator.kt`
- Modify: `domain/service/DetectionOrchestrator.kt` to use `ScoreAggregator`
- Tests: `test/.../ScoreAggregatorTest.kt`

### References

- [Source: architecture.md#Scoring Formula]
- [Source: architecture.md#Confidence Accumulation Model]
- [Source: architecture.md#ADR-02: Monotone Confidence Signal]
- [Source: architecture.md#ADR-04: Score NaN Guard]
- [Source: prd.md#4.2 L'Orchestrateur Réactif et Formule du Score]

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-5

### Debug Log References
None — all tests green on first run.

### Completion Notes List
- AC-1: `ScoreAggregator.computeAiProbability()` applies `Σ(Score_i × Weight_i × Confidence_i) / Σ(Weight_i × Confidence_i)`; zero-confidence rules are implicitly excluded via zero contribution to both numerator and denominator.
- AC-2: Denominator-zero guard returns `0.0f`; additional `isNaN()`/`isInfinite()` defensive check on result (ADR-04).
- AC-3: Monotone ratchet (`peakConfidence = maxOf(peak, raw)`) lives in `DetectionOrchestrator`; `ScoreAggregator` is stateless as per dev notes.
- `DetectionOrchestrator` refactored to inject `ScoreAggregator` via constructor (default = `ScoreAggregator()`).
- `RuleContribution` data class introduced in `ScoreAggregator.kt` to decouple aggregator from the port interface.

### File List
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/service/ScoreAggregator.kt` (new)
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/service/DetectionOrchestrator.kt` (modified — ScoreAggregator injection + awaitAll refactor)
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/service/ScoreAggregatorTest.kt` (new)
- `docs/implementation-artifacts/story-1.3-compute-safe-weighted-ai-probability.md` (updated)
