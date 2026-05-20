# Story 2.5: Reduce Heavy Analysis During Clearly Human or Stable Audio Segments

Status: ready-for-dev

## Story

As a product team member,
I want the engine to reduce unnecessary heavy analysis when human evidence is already strong or speech is stable,
so that detection remains mobile-efficient without losing meaningful responsiveness.

## Acceptance Criteria

1. When `NoiseLinearityRule` returns strong organic-human evidence for a chunk, the orchestrator can skip `SpectralArtifactsRule` for that chunk following the defined early-exit policy.
2. During stable monologues (low RMS variance, no silence boundaries), heavier analysis paths can be suspended while passive noise monitoring continues.
3. On a silence boundary, speech transition, or energy spike, full analysis resumes automatically without manual intervention.

## Tasks / Subtasks

- [ ] Implement early-exit check in `DetectionOrchestrator`: if `NoiseLinearityRule` result has `suspicionScore < 0.05f` and `confidence == 1.0f`, skip dispatching `SpectralArtifactsRule` for that chunk (AC: 1)
- [ ] Implement RMS variance monitoring in the audio chunker or orchestrator: compute sliding 2 s window variance (AC: 2)
- [ ] Implement intermittent sampling mode: when RMS variance is below threshold and no silence/transition detected, suspend R-01 and R-02 dispatch, keep R-03 active (AC: 2)
- [ ] Implement transition detection: resume full analysis on silence event, speech turn, or RMS energy spike (AC: 3)
- [ ] Write unit test: `NoiseLinearityRule` low suspicion + full confidence → `SpectralArtifactsRule` not invoked for that chunk (AC: 1)
- [ ] Write unit test: stable monologue simulation → only R-03 runs during stable period (AC: 2)
- [ ] Write unit test: energy spike injected mid-monologue → full three-rule analysis resumes (AC: 3)

## Dev Notes

- Early-exit threshold (architecture): `suspicionScore < 0.05` with `confidence = 1.0` from R-03.
- Intermittent sampling target (architecture): suspend R-01 and R-02 during stable monologues; R-03 always active.
- Sliding window: 2 s = 4 chunks at 500 ms each.
- Transitions that trigger resume: silence boundary, speech-turn switch, or RMS energy spike above threshold.
- This story modifies `DetectionOrchestrator` dispatch logic — be careful not to break the parallelism pattern from Story 1.4.
- The early-exit and intermittent sampling are the primary battery-saving mechanisms (PRD § 4.3).

### Project Structure Notes

- Modifies: `domain/service/DetectionOrchestrator.kt` — add early-exit logic and RMS variance tracking
- May add: audio chunker RMS tracking utility, or inline in orchestrator
- Tests: `test/.../DetectionOrchestratorEarlyExitTest.kt`

### References

- [Source: architecture.md#Early Exit]
- [Source: architecture.md#Intermittent Sampling]
- [Source: architecture.md#Performance Architecture]
- [Source: prd.md#4.3 Optimisations Matérielles]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

