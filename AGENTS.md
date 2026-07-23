# Git Worktree Manager — Agent Instructions

JetBrains plugin for managing Git worktrees, with Claude Code session resume.

## Build & Test

- Full suite: `./gradlew test`
- Single class: `./gradlew test --tests ClaudeCodeContextServiceTest`
- First build downloads the IntelliJ SDK (several minutes); later runs ~20s–2m
- JDK 21 toolchain, Kotlin 2.1.20

## Layout

- `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/`
  - `services/` — business logic (GitWorktreeService, ClaudeCodeContextService, …)
  - `models/` — data classes
  - `ui/dialogs/` — Swing dialogs; `MyToolWindow.kt` — Compose tool window
  - `viewmodel/` — state management
- `src/test/kotlin/...` mirrors main; tests use `kotlin.test` (JUnit 5)

## Conventions

- Kotlin + kotlinx-serialization-json for JSON
- Descriptive variable names; no comments unless asked
- Private helpers in same file; companion object for statics

## Gotchas

- `.intellijPlatform/self-update.lock` is tracked but churns locally — don't commit it
- `version` in `build.gradle.kts` must exactly match the release tag (`vX.Y.Z`) or CI publish fails
- Release: `RELEASE_RUNBOOK.md` (canonical) + `.opencode/instructions/release-workflow.md`

## Pull Requests — use no-mistakes

- This repo has a no-mistakes gate (remote `no-mistakes`). Create feature/fix PRs through it:
  1. Commit work on a feature branch
  2. Run `no-mistakes update` to ensure the latest gate version is installed before pushing
  3. `git push no-mistakes <branch>` — the gate runs review → test → document → lint → push → PR → CI in an isolated worktree and opens the PR when everything is green
  4. Monitor non-interactively: `no-mistakes axi status` (or `no-mistakes` for the TUI)
- Don't push feature branches directly to `origin` for PRs — let the gate validate first.
- Exception: release PRs (`release/vX.Y.Z`) follow `RELEASE_RUNBOOK.md` — pushed to `origin` directly, since they rely on the auto-change-notes bot + tag-driven publishing.

### Monitoring no-mistakes pipelines

- Use `no-mistakes axi status` for pipeline step progress (review → test → document → lint → push → pr → ci).
- For the **merge truth**, use GitHub directly, not the gate: `gh pr view <N> --json state,mergedAt` — the gate's `pr_state` may lag behind manual merges or fast GitHub updates.
- When polling, always add an exit condition instead of a blind loop:
  - Pipeline done: `until no-mistakes axi status | grep -q "status: completed"; do sleep 30; done`
  - PR merged: `until gh pr view <N> --json state -q '.state' | grep -q MERGED; do sleep 30; done`
- Don't truncate output with `head` while watching; grep the exact fields you need (`status:`, `branch_sync.state`, `pr_state`).
- On terminal state (`completed` / `MERGED`), stop polling immediately and proceed: `git checkout master && git pull`.
- For CI status of a PR, prefer `gh pr checks <N> --watch` or `gh run watch <run-id>`.

## CI

- PRs run `test` + `verifyPluginStructure`
- Pushing a `vX.Y.Z` tag on `master` triggers the **Release to JetBrains Marketplace** workflow (builds, signs, publishes)
