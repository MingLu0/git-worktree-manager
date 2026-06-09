## Current Feature Status

**Feature:** Copy Claude Code Context to new worktrees
**Status:** ✅ Complete

The original "copy ignored files" feature (designed in `docs/hyperpowers/designs/2026-01-30-copy-ignored-files-design.md`) was replaced by a focused Claude Code context copy feature. The old `IgnoredFilesService`, `FileOperationsService`, `CopyResult`, `IgnoredFileInfo`, `IgnoredFilesSelectionDialog`, and `CopyResultDialog` classes were removed.

### What was built

- **`ClaudeCodeContextService`**: detects `.claude/` project context and `~/.claude/projects/<key>/` session history; copies selected options while excluding private/local files and skipping existing session destinations.
- **`AgentContextCopyOption`**: model for a copy choice (id, display name, source/destination paths, type, selected flag, sensitive flag).
- **`AgentContextCopyResult`**: model tracking copied, skipped, and failed items with helper counts.
- **`AgentContextCopyDialog`**: dialog shown when context options are detected; project context is checked by default, session history is unchecked.
- **`AgentContextCopyResultDialog`**: shows a summary after copy completes.
- **`WorktreeState`**: replaced old ignored-files state fields with `agentContextCopyResult`.
- **`WorktreeViewModel`**: simplified; delegates context detection and copying to `ClaudeCodeContextService`.

### Key behaviours

- Session history copy is opt-in (unchecked by default) because sessions may contain secrets and local paths.
- Session history is skipped rather than overwritten if the destination already exists.
- `.claude/` copy excludes `settings.local.json`, `.env*`, and any path component matching `local`, `private`, `secret`, `secrets`, `token`, `tokens`, `credential`, or `credentials`.
- Session history copy is skipped on Windows (path encoding differs).
- Symlinks are not followed during copy to prevent root escapes.
