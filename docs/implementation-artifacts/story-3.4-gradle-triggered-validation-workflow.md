# Story 3.4: Run Validation as a Repeatable Gradle-Triggered Workflow

Status: ready-for-dev

## Story

As a development team member,
I want validation to run through a standard project task,
so that the engine can be benchmarked consistently during ongoing implementation.

## Acceptance Criteria

1. The validation harness runs through a Gradle-driven entry point using the configured dataset and engine pipeline consistently.
2. The Gradle task remains stable, easy to invoke repeatedly, and supports regression tracking against the same success criteria.
3. The task is scoped to engine evaluation only — no UI or OS integration concerns — and stays compatible with the pure Phase 1 R&D scope.

## Tasks / Subtasks

- [ ] Create a Gradle task in `build.gradle.kts` (e.g., `validateEngine`) that invokes `ValidationRunner.main()` or equivalent entry point (AC: 1)
- [ ] Configure the task to accept a dataset directory path as a Gradle property or environment variable (AC: 1)
- [ ] Ensure the task runs on JVM only, with no Android Gradle plugin dependency (AC: 3)
- [ ] Document the task usage in a comment block in `build.gradle.kts` (AC: 2)
- [ ] Add a `ValidationRunner.main()` entry point or top-level Kotlin function that wires dataset path → audio source → orchestrator → report (AC: 1)
- [ ] Write a smoke-test or basic invocation test: assert `ValidationRunner` completes without error on a minimal test dataset (AC: 2)

## Dev Notes

- Gradle task type: use a `JavaExec` task or Kotlin `exec {}` block pointing to the main class.
- Dataset path configuration: `gradle validateEngine -PdatasetPath=/path/to/datasets` pattern.
- The `ValidationRunner` entry point wires together Stories 3.1 (audio replay), 3.2 (metrics), and 3.3 (latency).
- Keep the Gradle task simple — its job is to wire the JVM entry point, not duplicate business logic.
- Out of scope: Android Gradle plugin, instrumented tests, emulator integration.

### Project Structure Notes

- Modifies: root or module `build.gradle.kts` — add `validateEngine` Gradle task
- Modifies: `harness/ValidationRunner.kt` — add `main()` entry point
- Tests: `test/.../ValidationRunnerSmokeTest.kt`

### References

- [Source: architecture.md#Validation Strategy — Gradle-triggered batch scorer]
- [Source: architecture.md#Package Structure — harness/ValidationRunner.kt]
- [Source: prd.md#6. Critères de Validation & Stratégie de Test]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

