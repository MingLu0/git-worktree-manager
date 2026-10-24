# Git Worktree Manager — Agent Instructions

JetBrains plugin for managing Git worktrees, with Claude Code session resume.

## Build & Test

- Full suite: `./gradlew test`
- Single class: `./gradlew test --tests ClaudeCodeContextServiceTest`
- First build downloads the IntelliJ SDK (several minutes); later runs ~20s–2m
- JDK 21 (jvmTarget 21), Kotlin 2.1.20

## Layout

- `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/`
  - `MyToolWindow.kt` — Compose tool window (package root)
  - `services/` — business logic (GitWorktreeService, ClaudeCodeContextService, …)
  - `repository/` — `WorktreeRepository` + contract
  - `models/` — data classes; `exceptions/` — worktree exceptions
  - `ui/dialogs/` — Swing dialogs
  - `viewmodel/` — state management
- `src/test/kotlin/...` mirrors main; tests use `kotlin.test` on the JUnit 4 runner (no `useJUnitPlatform()`) — don't use `org.junit.jupiter`, those tests won't run

## Conventions

- Kotlin + kotlinx-serialization-json for JSON
- Descriptive variable names; no comments unless asked
- Private helpers in same file; companion object for statics

## Gotchas

- `.intellijPlatform/self-update.lock` is tracked but churns locally — don't commit it
- `version` in `build.gradle.kts` must exactly match the release tag (`vX.Y.Z`) or CI publish fails
- Release: `RELEASE_RUNBOOK.md` (canonical)

## Pull Requests

- Release PRs (`release/vX.Y.Z`) follow `RELEASE_RUNBOOK.md` — they rely on the auto-change-notes bot + tag-driven publishing.
- CI status: `gh pr checks <N> --watch` or `gh run watch <run-id>`.
- Merge state: `gh pr view <N> --json state,mergedAt`. When polling, use an exit condition, not a blind loop: `until gh pr view <N> --json state -q '.state' | grep -q MERGED; do sleep 30; done`

## CI

- PRs run `test` + `verifyPluginStructure`
- Pushing a `vX.Y.Z` tag on `master` triggers the **Release to JetBrains Marketplace** workflow (builds, signs, publishes)
