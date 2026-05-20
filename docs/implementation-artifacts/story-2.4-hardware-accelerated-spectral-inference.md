# Story 2.4: Support Hardware-Accelerated Spectral Inference for Production Use

Status: ready-for-dev

## Story

As a platform engineer,
I want the spectral-classification adapter to use hardware-accelerated local inference in production,
so that the engine can run mobile-capable AI audio analysis without unacceptable CPU cost.

## Acceptance Criteria

1. When hardware acceleration is available, the adapter uses an NNAPI-compatible or equivalent hardware-accelerated delegate path and exposes classifier output to `SpectralArtifactsRule`.
2. When hardware acceleration is unavailable, the adapter does not silently fall back to CPU inference and surfaces the unsupported state clearly.
3. Hardware-specific logic remains isolated from the domain layer; the domain-facing rule contract stays pure Kotlin/JVM-friendly.

## Tasks / Subtasks

- [ ] Create `adapters/TFLiteSpectralAdapter.kt` implementing `SpectralClassifierPort` (AC: 1, 3)
- [ ] Load the `.tflite` model file and configure `NnApiDelegate` or `GpuDelegate` for hardware acceleration (AC: 1)
- [ ] Implement an availability check: detect if hardware acceleration is supported at initialization (AC: 2)
- [ ] Throw or surface a clear error/warning when hardware acceleration is unavailable — do not silently use CPU fallback in production code (AC: 2)
- [ ] Verify that `TFLiteSpectralAdapter` implements `SpectralClassifierPort` — domain contract unchanged (AC: 3)
- [ ] Write integration test (or manual test documentation): adapter initializes successfully when NNAPI delegate is available (AC: 1)
- [ ] Write unit test for availability-check path: assert that unavailability is surfaced, not silently swallowed (AC: 2)

## Dev Notes

- This story introduces the Android/TFLite binding — it lives strictly in `adapters/`, never in `domain/` or `rules/` (ADR-01).
- `SpectralClassifierPort` from Story 2.3 remains unchanged — adapter simply implements it.
- TFLite model file: compile to `.tflite`, load via `Interpreter(modelBuffer, options)` with `NnApiDelegate`.
- Hardware acceleration target: Tensor G4 NPU on Pixel 9a.
- Unavailability handling: log a warning and throw an `IllegalStateException` or return a result that the orchestrator can handle; do not fall back to CPU silently.
- Note: end-to-end TFLite validation is Phase 2 hardware work; this story establishes the adapter wiring and the policy for hardware requirements.
- The `FakeSpectralClassifier` from Story 2.3 remains the test double for all non-adapter unit tests.

### Project Structure Notes

- New file: `adapters/TFLiteSpectralAdapter.kt`
- No changes to `domain/` or `rules/` packages
- Tests: integration test or documented manual test for hardware delegate path

### References

- [Source: architecture.md#TPU Delegation (TFLite)]
- [Source: architecture.md#Performance Architecture]
- [Source: architecture.md#ADR-01: Pure-JVM Domain in Phase 1]
- [Source: architecture.md#Package Structure — adapters/TFLiteSpectralAdapter.kt]
- [Source: prd.md#4.3 Optimisations Matérielles]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

