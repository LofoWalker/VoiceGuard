# Story 1.4: Execute Rule Analysis in Parallel with a Controlled Context Boundary

Status: review

## Story

As a platform engineer,
I want rule analysis to run in parallel with an injected dispatcher and controlled shared context access,
so that the engine remains testable, concurrent, and free from race-prone rule behavior.

## Acceptance Criteria

1. The orchestrator executes rule analysis in parallel for each chunk using an injected, non-hardcoded dispatcher.
2. Tests can substitute an `UnconfinedTestDispatcher` and verify orchestration behavior without platform dependencies.
3. Rules can read `ConversationContext` during concurrent execution; only the orchestrator mutates it.

## Tasks / Subtasks

- [x] Refactor `DetectionOrchestrator` to accept a `CoroutineDispatcher` via constructor injection (AC: 1, 2)
- [x] Implement parallel chunk dispatch: `coroutineScope { rules.map { async { it.analyze(chunk, ctx) } }.awaitAll() }` (AC: 1)
- [x] Ensure `ConversationContext` updates (speech timestamps, duration) happen in the orchestrator only, after `awaitAll()` (AC: 3)
- [x] Write unit test using `UnconfinedTestDispatcher`: submit a chunk, assert all rule results are collected before state emission (AC: 2)
- [x] Write unit test: parallel rules reading `ConversationContext` concurrently do not observe mutations mid-analysis (AC: 3)
- [x] Write unit test: orchestrator mutates `ConversationContext.lastSpeechSwitchTimestamp` only after rules complete (AC: 3)

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
claude-sonnet-4-5

### Debug Log References
None — all tests green.

### Completion Notes List
- AC-1: `CoroutineDispatcher` injected via constructor; default is `Dispatchers.Default`. All `async` blocks use the injected dispatcher.
- AC-1: Parallel dispatch uses `rules.map { async(dispatcher) { ... } }.awaitAll()` — the canonical pattern from architecture.md.
- AC-2: `UnconfinedTestDispatcher` substitution verified in `DetectionOrchestratorParallelTest` — all tests run synchronously and deterministically.
- AC-3: `context.updateCallDuration()` moved strictly after `awaitAll()`. Rules observe `callDurationMillis = 0` (pre-chunk snapshot) during their execution, verified by a capturing-rule test using reflection.
- Reflective accessor `getContextCallDuration()` used in tests to inspect private `context` field without exposing it as public API.

### File List
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/service/DetectionOrchestrator.kt` (modified — context update after awaitAll, awaitAll() call site)
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/service/DetectionOrchestratorParallelTest.kt` (new)
- `docs/implementation-artifacts/story-1.4-parallel-rule-execution-context-boundary.md` (updated)
