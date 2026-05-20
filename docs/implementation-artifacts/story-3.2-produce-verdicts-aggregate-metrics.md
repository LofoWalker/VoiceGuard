# Story 3.2: Produce Per-File Verdicts and Aggregate Evaluation Metrics

Status: ready-for-dev

## Story

As a product and research team member,
I want the validation harness to report file-level outcomes and dataset-level metrics,
so that I can judge whether the engine is meeting the Phase 1 detection goals.

## Acceptance Criteria

1. The harness produces a verdict for each processed audio file, available for later inspection or summary.
2. Aggregated results include overall accuracy and false positive rate, sufficient to compare against the Phase 1 KPI targets (≥ 85% accuracy, ≤ 5% FPR).
3. The summary clearly distinguishes aggregate performance from individual sample outcomes and supports identifying underperforming segments.

## Tasks / Subtasks

- [ ] Define a `ValidationVerdict` data class: file path, ground-truth label (human/AI), engine verdict, final `aiProbability`, final `globalConfidence` (AC: 1)
- [ ] Implement per-file verdict collection in `ValidationRunner`: after each file completes, record the final `DetectionUiState` and compare to ground truth (AC: 1)
- [ ] Implement accuracy computation: correct classifications / total files (AC: 2)
- [ ] Implement false positive rate computation: human files classified as AI / total human files (AC: 2)
- [ ] Implement a summary report output (stdout or file): totals, accuracy %, FPR %, list of misclassified files (AC: 3)
- [ ] Write unit test: mock orchestrator results for a mix of human and AI files → assert accuracy and FPR computed correctly (AC: 2)
- [ ] Write unit test: misclassified files appear prominently in summary output (AC: 3)

## Dev Notes

- Ground truth label source: derive from dataset directory structure (e.g. `real/` vs `fake/` subdirectories used by FoR-rerec and HuggingFace datasets).
- Verdict threshold: classify as AI if final `aiProbability` exceeds a configurable threshold (default 0.5); only count when `globalConfidence >= 0.6` (architecture: unreliable before that).
- Phase 1 KPI targets: accuracy ≥ 85%, FPR ≤ 5% (PRD § 6.2).
- `ValidationRunner` should remain a pure computation layer — no interaction with Android or UI.

### Project Structure Notes

- Modifies: `harness/ValidationRunner.kt` — add verdict collection and metric aggregation
- New: `harness/ValidationVerdict.kt` data class
- Tests: `test/.../ValidationRunnerMetricsTest.kt`

### References

- [Source: architecture.md#Validation Strategy]
- [Source: prd.md#6.2 KPIs de Réussite Technique]
- [Source: prd.md#6.1 Datasets cibles]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

