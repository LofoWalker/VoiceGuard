# Story 1.5: Enforce Informative-Only Engine Behavior for Phase 1

Status: review

## Story

As a product team member,
I want the engine to remain strictly advisory in Phase 1,
so that the system informs human judgment without taking irreversible action on live calls.

## Acceptance Criteria

1. The engine exposes analysis signals only and does not include any behavior that terminates, blocks, or interrupts calls.
2. The engine remains a pure Kotlin JVM core with no Android-specific call-control dependency in the detection domain.

## Tasks / Subtasks

- [x] Audit all domain and rules packages: assert no `android.*`, `telephony.*`, or call-control import exists (AC: 2)
- [x] Add an automated build check or test that fails if any Android SDK dependency is introduced into `domain/` or `rules/` (AC: 2)
- [x] Document in `DetectionOrchestrator` KDoc that the engine is informative-only and never triggers call actions (AC: 1)
- [x] Verify `DetectionUiState` exposes only informational fields — no action flags or call-control hooks (AC: 1)
- [x] Write a compile-time or test-level assertion verifying the domain module gradle configuration excludes Android SDK (AC: 2)

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
claude-sonnet-4-5

### Debug Log References
None.

### Completion Notes List
- AC-1: `DetectionOrchestrator` KDoc updated with explicit "Informative-only" statement and reference to PRD Phase 1 / ADR-01.
- AC-1: `DetectionUiState` KDoc updated with "Informative-only" contract; test asserts no call-action field name exists now or in future.
- AC-2: `DomainScopeComplianceTest` verifies: no `android.content.Context` or `android.telecom.*` on classpath, no Android supertype on domain classes, `build.gradle.kts` free of `com.android` declarations, `DetectionUiState` fields match exactly the three expected informational fields.
- Audit result: zero Android/telephony imports found across domain and service packages.

### File List
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/model/DetectionUiState.kt` (KDoc updated)
- `voiceguard-engine/src/main/kotlin/com/voiceguard/domain/service/DetectionOrchestrator.kt` (KDoc updated)
- `voiceguard-engine/src/test/kotlin/com/voiceguard/domain/DomainScopeComplianceTest.kt` (new)
- `docs/implementation-artifacts/story-1.5-enforce-informative-only-engine-behavior.md` (updated)
