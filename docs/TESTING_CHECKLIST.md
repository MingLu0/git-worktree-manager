# Manual Testing Checklist - Claude Code Context Copy

## Create without Claude context

1. Open a Git repository with no `.claude/` directory.
2. Click `Create Worktree`.
3. Enter a worktree name and branch name.
4. Verify no context-copy dialog appears.
5. Verify the worktree is created and opened normally.

## Copy safe `.claude/` project context

1. Add `.claude/commands/review.md` to the source worktree.
2. Add `.claude/settings.local.json` to the source worktree.
3. Click `Create Worktree`.
4. Verify `Copy Coding Agent Context` appears.
5. Verify `Claude Code project context (.claude/)` is checked by default.
6. Leave session history unchecked.
7. Create the worktree.
8. Verify `.claude/commands/review.md` exists in the new worktree.
9. Verify `.claude/settings.local.json` does not exist in the new worktree.

## Session history opt-in

1. Use a repo that has Claude Code session history under `~/.claude/projects/`.
2. Click `Create Worktree`.
3. Verify `Claude Code session history` appears unchecked by default.
4. Check it explicitly.
5. Create the worktree.
6. Open a terminal in the new worktree and run `claude --resume`.
7. Verify copied sessions are available for the new worktree path.

## Existing destination session history

1. Create a worktree that already has a Claude session directory under `~/.claude/projects/`.
2. Create another worktree using the same destination name/path.
3. Select session-history copy.
4. Verify the result dialog reports the session history was skipped rather than overwritten.
