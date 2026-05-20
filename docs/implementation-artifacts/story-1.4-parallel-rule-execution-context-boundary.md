# Story 1.4: Execute Rule Analysis in Parallel with a Controlled Context Boundary

Status: ready-for-dev

## Story

As a platform engineer,
I want rule analysis to run in parallel with an injected dispatcher and controlled shared context access,
so that the engine remains testable, concurrent, and free from race-prone rule behavior.

## Acceptance Criteria

1. The orchestrator executes rule analysis in parallel for each chunk using an injected, non-hardcoded dispatcher.
2. Tests can substitute an `UnconfinedTestDispatcher` and verify orchestration behavior without platform dependencies.
3. Rules can read `ConversationContext` during concurrent execution; only the orchestrator mutates it.

## Tasks / Subtasks

- [ ] Refactor `DetectionOrchestrator` to accept a `CoroutineDispatcher` via constructor injection (AC: 1, 2)
- [ ] Implement parallel chunk dispatch: `coroutineScope { rules.map { async { it.analyze(chunk, ctx) } }.awaitAll() }` (AC: 1)
- [ ] Ensure `ConversationContext` updates (speech timestamps, duration) happen in the orchestrator only, after `awaitAll()` (AC: 3)
- [ ] Write unit test using `UnconfinedTestDispatcher`: submit a chunk, assert all rule results are collected before state emission (AC: 2)
- [ ] Write unit test: parallel rules reading `ConversationContext` concurrently do not observe mutations mid-analysis (AC: 3)
- [ ] Write unit test: orchestrator mutates `ConversationContext.lastSpeechSwitchTimestamp` only after rules complete (AC: 3)

## Dev Notes

- This story upgrades the orchestrator from Story 1.2 to full parallel execution.
- The dispatcher field type: `CoroutineDispatcher` injected at construction time; default to `Dispatchers.Default` in production.
- Test pattern: `DetectionOrchestrator(dispatcher = UnconfinedTestDispatcher())`.
- Rules are stateless per chunk — they read context but never write to it (ADR-03).
- `ConversationContext` is passed by reference; rules must not hold a mutable reference and call setters.
- Use `@GuardedBy` documentation or equivalent comment to make thread-safety contract explicit.

### Project Structure Notes

- Modify: `domain/service/DetectionOrchestrator.kt` — add dispatcher injection, parallel dispatch block
- Tests: `test/.../DetectionOrchestratorParallelTest.kt`

### References

- [Source: architecture.md#Orchestration Engine]
- [Source: architecture.md#ADR-03: Rules Are Stateless Per-Chunk Except for Context Injection]
- [Source: prd.md#4.2 L'Orchestrateur Réactif et Formule du Score]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

