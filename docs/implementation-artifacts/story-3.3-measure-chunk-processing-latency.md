# Story 3.3: Measure Chunk-Processing Latency Against the Real-Time Budget

Status: ready-for-dev

## Story

As a platform engineer,
I want the validation harness to measure processing time around orchestrator execution,
so that I can verify the engine stays within the per-chunk latency budget.

## Acceptance Criteria

1. For each chunk, the harness measures processing time using timing guards around the orchestrator dispatch path and records data suitable for comparison with the 50 ms target.
2. The run summary reports whether observed processing stays within the per-chunk budget and highlights any violations clearly.
3. The latency measurement approach remains consistent across repeated runs in the same environment and avoids dependence on microphone or live-call conditions.

## Tasks / Subtasks

- [ ] Add timing guards in `ValidationRunner` around each orchestrator chunk dispatch: `System.nanoTime()` before and after (AC: 1)
- [ ] Record per-chunk latency in nanoseconds; convert to milliseconds for reporting (AC: 1)
- [ ] Compute per-run latency statistics: mean, max, and count of budget violations (> 50 ms) (AC: 2)
- [ ] Include latency summary in the validation report output alongside accuracy and FPR (AC: 2)
- [ ] Write unit test: inject a fake orchestrator with known processing delay → assert latency recorded correctly (AC: 1)
- [ ] Write unit test: 3 chunks within budget, 1 chunk exceeds 50 ms → assert violation count == 1 in summary (AC: 2)

## Dev Notes

- Timing approach: `System.nanoTime()` around each `orchestrator.process(chunk)` call (architecture: "measured with `System.nanoTime()` guards around the orchestrator dispatch loop").
- Budget: 50 ms per 500 ms chunk (PRD § 6.2, NFR2).
- Latency is measured on JVM only for Phase 1; native Android timing is Phase 2.
- Keep timing guards minimal: measure only the orchestrator dispatch, not file I/O or chunk segmentation.
- Avoid `System.currentTimeMillis()` for latency measurement — prefer `System.nanoTime()` for sub-millisecond resolution.

### Project Structure Notes

- Modifies: `harness/ValidationRunner.kt` — add timing guards and latency stats aggregation
- Tests: `test/.../ValidationRunnerLatencyTest.kt`

### References

- [Source: architecture.md#Validation Strategy]
- [Source: prd.md#6.2 KPIs de Réussite Technique — Temps de calcul]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

