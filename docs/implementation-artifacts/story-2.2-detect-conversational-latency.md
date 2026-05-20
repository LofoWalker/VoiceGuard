# Story 2.2: Detect Suspicious Conversational Latency After Speech Turns

Status: ready-for-dev

## Story

As a research engineer,
I want the engine to evaluate response timing after a speech-turn switch,
so that it can identify latency patterns consistent with an STT-to-LLM-to-TTS pipeline.

## Acceptance Criteria

1. Before a speech-turn switch is observed, `LatencyBehaviorRule` returns `confidence = 0.0` and does not influence weighted AI probability.
2. After `ConversationContext` records a speech-turn switch, the rule scores narrow repeated delays in the suspicious AI-response range higher than human-like variable delays.
3. Normal conversational variance is not classified as strongly suspicious; the rule remains compatible with the orchestrator-owned context model.

## Tasks / Subtasks

- [ ] Create `rules/LatencyBehaviorRule.kt` implementing `AudioDetectionRule` (weight = 0.40) (AC: 1)
- [ ] Implement warm-up: return `RuleResult(suspicionScore = 0.0f, confidence = 0.0f)` until first speech-turn switch is recorded in `ConversationContext` (AC: 1)
- [ ] Implement latency suspicion scoring: delays narrowly clustered in the 1.5–2.2 s range score high; biologically variable 180–350 ms range scores low (AC: 2)
- [ ] Implement multi-turn accumulation: confidence increases with each observed speech switch (AC: 2)
- [ ] Ensure the rule reads `ConversationContext.lastSpeechSwitchTimestamp` without mutating the context (AC: 3)
- [ ] Write unit test: no speech switches recorded → `confidence == 0.0f` (AC: 1)
- [ ] Write unit test: repeated 1.8 s delays after speech switches → high suspicion score (AC: 2)
- [ ] Write unit test: variable 200–400 ms delays → low suspicion score (AC: 3)

## Dev Notes

- Weight: 0.40 — highest weight of the three rules; this is the definitive behavioral signal.
- Activation: only after first speech switch detected — `confidence = 0.0` until then (ADR exclusion logic in ScoreAggregator).
- Suspicious latency window: ≈ 1.5–2.2 s (STT → LLM → TTS cloud pipeline round trip).
- Human variability window: 180–350 ms with high variance.
- The rule reads `ConversationContext` by reference but never calls setters — strictly read-only from the rule's perspective (ADR-03).
- The orchestrator is responsible for writing speech-switch timestamps to `ConversationContext` based on audio energy transitions.

### Project Structure Notes

- New file: `rules/LatencyBehaviorRule.kt`
- Tests: `test/.../LatencyBehaviorRuleTest.kt`
- Depends on: Story 1.1 (domain contracts), Story 1.4 (orchestrator context management)

### References

- [Source: architecture.md#R-01 — LatencyBehaviorRule]
- [Source: architecture.md#Detection Rules table]
- [Source: architecture.md#ADR-03: Rules Are Stateless Per-Chunk Except for Context Injection]
- [Source: prd.md#5. Spécifications des Règles Pilotes — R-01]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

