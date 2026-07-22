## Current Feature Status

**Feature:** Native Claude Code Session Resume
**Status:** ✅ Complete

The earlier Claude Code context-copy flow was replaced by native Claude Code session discovery and forked resume. The removed copy flow no longer copies `.claude/` project context or session history into new worktrees.

### What was built

- **`ClaudeCodeContextService`**: lists Claude session JSONL files for known repository worktrees under `~/.claude/projects/<key>/` and extracts session titles, ids, source paths, and modification times.
- **`ClaudeSessionInfo`**: model for a discovered Claude session.
- **`WorktreeState`**: tracks Claude sessions, session loading state, and session loading errors alongside worktree state.
- **`WorktreeViewModel`**: refreshes Claude sessions after worktrees load and delegates session discovery to `ClaudeCodeContextService`.
- **`MyToolWindow`**: shows collapsible `Claude Sessions` UI and resumes sessions in a terminal with `claude --resume <session-id> --fork-session`.

### Key behaviours

- Sessions from the current project are sorted before sessions from other known worktrees, then by most recent modification time.
- Resume validates that the session id is a UUID before passing it to the terminal command.
- Resume uses Claude Code's native forked resume instead of copying or rewriting session files.
- Session discovery returns no sessions on Windows because Claude Code's project-path encoding differs there.
