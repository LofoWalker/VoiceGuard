# Story 1.5: Enforce Informative-Only Engine Behavior for Phase 1

Status: ready-for-dev

## Story

As a product team member,
I want the engine to remain strictly advisory in Phase 1,
so that the system informs human judgment without taking irreversible action on live calls.

## Acceptance Criteria

1. The engine exposes analysis signals only and does not include any behavior that terminates, blocks, or interrupts calls.
2. The engine remains a pure Kotlin JVM core with no Android-specific call-control dependency in the detection domain.

## Tasks / Subtasks

- [ ] Audit all domain and rules packages: assert no `android.*`, `telephony.*`, or call-control import exists (AC: 2)
- [ ] Add an automated build check or test that fails if any Android SDK dependency is introduced into `domain/` or `rules/` (AC: 2)
- [ ] Document in `DetectionOrchestrator` KDoc that the engine is informative-only and never triggers call actions (AC: 1)
- [ ] Verify `DetectionUiState` exposes only informational fields — no action flags or call-control hooks (AC: 1)
- [ ] Write a compile-time or test-level assertion verifying the domain module gradle configuration excludes Android SDK (AC: 2)

## Dev Notes

- This story is primarily a scope-enforcement and architectural guard story, not a feature story.
- The most reliable approach: configure the `domain` and `rules` Gradle modules to depend only on the Kotlin standard library and `kotlinx-coroutines-core` — no `implementation("com.android.*")` allowed.
- Consider a dedicated ArchUnit or a simple import-scanner test to catch accidental Android imports.
- `DetectionUiState` fields: `globalConfidence`, `aiProbability`, `elapsedSeconds` — verify no call-action fields are present or added.
- Out of scope for Phase 1: `InCallService`, VoIP interception, notification actions, call termination APIs.

### Project Structure Notes

- Touches: `domain/` module `build.gradle.kts` for dependency constraint
- Touches: `domain/model/DetectionUiState.kt` docstring/review
- New test: `test/.../DomainScopeComplianceTest.kt` or equivalent

### References

- [Source: architecture.md#ADR-01: Pure-JVM Domain in Phase 1]
- [Source: prd.md#2. Portée du Projet — Hors Périmètre]
- [Source: prd.md#3.3 Comportement face aux Faux Positifs]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

