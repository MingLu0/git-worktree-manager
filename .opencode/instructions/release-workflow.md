---
name: release-workflow
description: Git Worktree Manager release process for JetBrains Marketplace. Use when bumping versions, cutting releases, or publishing to the Marketplace.
---

# Release Workflow

When the user says "bump version", "release", "publish", or similar, follow this checklist:

1. **Bump version** in `build.gradle.kts` — update `version = "x.y.z"`
2. **Open release PR** — branch `release/vX.Y.Z`, push, open PR (auto-change-notes runs via CI)
3. **Wait for CI** on the release PR to pass
4. **Merge release PR** to `master`
5. **Pull latest master** locally: `git checkout master && git pull`
6. **Create and push tag**: `git tag -a vX.Y.Z -m "Release vX.Y.Z" && git push origin vX.Y.Z`
7. **Monitor the workflow** at `https://github.com/MingLu0/git-worktree-manager/actions`
8. **Verify** the new version appears on JetBrains Marketplace
