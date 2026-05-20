# Story 2.3: Detect Spectral Artifacts with a Local Classifier Abstraction

Status: ready-for-dev

## Story

As a research engineer,
I want the engine to score vocoder-like spectral artifacts through a local classifier interface,
so that synthetic speech signals can be detected incrementally without coupling the domain to Android bindings.

## Acceptance Criteria

1. `SpectralArtifactsRule` obtains a classifier result through an adapter-friendly abstraction and converts it into a suspicion score and confidence contribution.
2. Its confidence grows incrementally across successive chunks as the model accumulates evidence.
3. A fake spectral classifier or equivalent test double can be substituted in JVM tests with no Android or hardware inference dependency.

## Tasks / Subtasks

- [ ] Define `SpectralClassifierPort` interface in `domain/port/` — single `classify(chunk: AudioChunk): Float` contract (AC: 3)
- [ ] Create `rules/SpectralArtifactsRule.kt` implementing `AudioDetectionRule` (weight = 0.35), accepting `SpectralClassifierPort` via constructor (AC: 1)
- [ ] Implement incremental confidence: start at 0.0 and increment per chunk toward 1.0 as scores accumulate (AC: 2)
- [ ] Create `adapters/FakeSpectralClassifier.kt` in the test source set: returns pre-seeded configurable scores for JVM unit tests (AC: 3)
- [ ] Write unit test using `FakeSpectralClassifier`: several AI-scored chunks → increasing confidence, high suspicion (AC: 2)
- [ ] Write unit test: human-scored chunks → low suspicion accumulation (AC: 1)
- [ ] Write unit test: `SpectralArtifactsRule` constructed in JVM without any native or Android library on classpath (AC: 3)

## Dev Notes

- Weight: 0.35.
- The abstraction `SpectralClassifierPort` lives in `domain/port/` — this keeps the rule domain-clean (ADR-01).
- The production TFLite adapter (`TFLiteSpectralAdapter`) will implement `SpectralClassifierPort` and live in `adapters/` — not needed for this story.
- `FakeSpectralClassifier` in test source set (`src/test/`) accepts constructor-injected seed scores for determinism.
- Incremental confidence model: simple linear ramp over N chunks, or a moving average — keep it straightforward for Phase 1.
- Vocoder artifact signatures targeted: ElevenLabs, OpenAI TTS, Kokoro — quantization scars, band-limited harmonics, periodic spectral tiling.

### Project Structure Notes

- New file: `domain/port/SpectralClassifierPort.kt`
- New file: `rules/SpectralArtifactsRule.kt`
- New test file: `test/.../FakeSpectralClassifier.kt` (or `adapters/FakeSpectralClassifier.kt` under test sources)
- Tests: `test/.../SpectralArtifactsRuleTest.kt`

### References

- [Source: architecture.md#R-02 — SpectralArtifactsRule]
- [Source: architecture.md#Detection Rules table]
- [Source: architecture.md#ADR-01: Pure-JVM Domain in Phase 1]
- [Source: architecture.md#Package Structure — adapters/TFLiteSpectralAdapter.kt]
- [Source: prd.md#5. Spécifications des Règles Pilotes — R-02]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

