# Manual Testing Checklist - Claude Code Session Resume

## Create a worktree

1. Open a Git repository.
2. Click `Create Worktree`.
3. Enter a worktree name and branch name.
4. Verify the worktree is created and opened normally.

## List Claude sessions

1. Use a repo with Claude Code session history under `~/.claude/projects/` for the current worktree and at least one other known worktree.
2. Open the `Git Worktrees` tool window.
3. Expand `Claude Sessions`.
4. Verify sessions are listed with titles and source worktree names.
5. Verify sessions from the current project are ordered before sessions from other worktrees.

## Resume a Claude session

1. Expand `Claude Sessions`.
2. Click `Resume` for a session.
3. Verify a terminal tab opens for the project.
4. Verify it runs `claude --resume <session-id> --fork-session`.
