# Story 2.1: Detect Suspicious Background-Noise Linearity

Status: ready-for-dev

## Story

As a research engineer,
I want the engine to identify digital silence and repeated ambient patterns,
so that it can produce early evidence that a caller may be artificially generated.

## Acceptance Criteria

1. `NoiseLinearityRule` detects suspiciously perfect silence and repeated loop-like noise patterns, returning a `RuleResult` with a suspicion score and confidence.
2. Within the first 2 seconds of audio, `NoiseLinearityRule` reaches its intended high-confidence operating range and can contribute to the global score without waiting for speech-turn switches.
3. Organic non-linear audio produces low suspicion outcomes and supports early-exit decisions without forcing false AI verdicts.

## Tasks / Subtasks

- [ ] Create `rules/NoiseLinearityRule.kt` implementing `AudioDetectionRule` (weight = 0.25) (AC: 1)
- [ ] Implement digital silence detection: near-zero RMS energy across a chunk signals high suspicion (AC: 1)
- [ ] Implement loop detection: compare noise texture similarity across successive chunks to flag repeating patterns (AC: 1)
- [ ] Implement confidence ramp: reach max confidence within 2 seconds (≈ 4 chunks at 500 ms each) (AC: 2)
- [ ] Ensure `NoiseLinearityRule` never mutates `ConversationContext` (AC: 1)
- [ ] Write unit test: perfect silence input → high suspicion score, confidence approaching 1.0 within 4 chunks (AC: 2)
- [ ] Write unit test: organic noisy input → low suspicion score (AC: 3)
- [ ] Write unit test: rule returns `confidence = 0.0` on first chunk (warm-up behavior, optional if confidence ramps immediately) — align with architecture confidence table (AC: 2)

## Dev Notes

- Weight: 0.25 (defined in architecture detection rules table).
- Confidence model (architecture): R-03 reaches max confidence by 2 s — implement a simple counter-based ramp over 4 chunks.
- DSP approach for this phase: pure JVM math (no NNAPI) for noise analysis.
- RMS silence threshold and loop-similarity threshold should be configurable constants or constructor parameters for test flexibility.
- This rule is designed to be the cheapest to run — it must complete well within the 50 ms per-chunk budget.
- Its output drives the early-exit decision in Story 2.5 — low suspicion + high confidence = skip spectral analysis.

### Project Structure Notes

- New file: `rules/NoiseLinearityRule.kt`
- Tests: `test/.../NoiseLinearityRuleTest.kt`
- Dependencies: domain contracts from Epic 1 (Story 1.1)

### References

- [Source: architecture.md#R-03 — NoiseLinearityRule]
- [Source: architecture.md#Detection Rules table]
- [Source: architecture.md#Confidence Accumulation Model]
- [Source: prd.md#5. Spécifications des Règles Pilotes — R-03]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

