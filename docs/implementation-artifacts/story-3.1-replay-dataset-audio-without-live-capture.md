# Story 3.1: Replay Dataset Audio Through the Engine Without Live Capture

Status: ready-for-dev

## Story

As a research engineer,
I want the validation harness to feed recorded dataset audio directly into the engine pipeline,
so that I can evaluate detection behavior without relying on microphone hardware or live call setup.

## Acceptance Criteria

1. The harness loads audio samples from a local dataset source and feeds them as replayed `AudioChunk` inputs rather than requiring microphone capture.
2. Dataset audio is produced in the expected 500 ms chunk cadence and preserves the evaluation flow used by the orchestrator and rules.
3. Replaying the same dataset subset produces the same deterministic input path without external runtime variability.

## Tasks / Subtasks

- [ ] Create `adapters/DatasetAudioSource.kt` implementing `AudioSourcePort` — reads local audio files and emits `AudioChunk` instances (AC: 1)
- [ ] Implement 500 ms segmentation: split raw audio files into `AudioChunk` slices at 16 kHz sample rate (AC: 2)
- [ ] Ensure the adapter is purely file-based with no microphone or OS audio capture dependency (AC: 1, 3)
- [ ] Create `harness/ValidationRunner.kt` — accepts a directory of audio files, iterates through them via `DatasetAudioSource`, feeds chunks into `DetectionOrchestrator` (AC: 1, 2)
- [ ] Write unit test: load a known test audio file, assert correct number of `AudioChunk` objects produced at correct sample rate (AC: 2)
- [ ] Write unit test: replay same file twice → identical `AudioChunk` sequence both times (AC: 3)

## Dev Notes

- `DatasetAudioSource` implements `AudioSourcePort` from domain — the port abstraction established in Story 1.1.
- Supported format for Phase 1: raw PCM or WAV at 16 kHz; use a simple JVM WAV reader (no Android MediaCodec).
- The harness bypasses the microphone path entirely — feeds `AudioChunk` objects directly to orchestrator (architecture: validation strategy).
- Target datasets: Fake-or-Real (FoR) rerecorded version and HuggingFace Deepfake-Audio-Detection.
- Sample rate: 16 kHz (telephony standard, matching `AudioChunk.sampleRate` default).

### Project Structure Notes

- New file: `adapters/DatasetAudioSource.kt`
- New file: `harness/ValidationRunner.kt`
- Tests: `test/.../DatasetAudioSourceTest.kt`

### References

- [Source: architecture.md#Validation Strategy]
- [Source: architecture.md#Package Structure — adapters/DatasetAudioSource.kt, harness/ValidationRunner.kt]
- [Source: prd.md#6.1 Datasets cibles]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

