package com.purringlabs.gitworktree.gitworktreemanager.models

/**
 * Represents information about a git worktree
 * @param path Absolute path to the worktree directory
 * @param commit The current commit hash
 * @param branch The branch name (if checked out to a branch)
 * @param isMain Whether this is the main worktree (not a linked worktree)
 */
data class WorktreeInfo(
    val path: String,
    val commit: String,
    val branch: String?,
    val isMain: Boolean = false
) {
    companion object {
        /**
         * Parses a line from `git worktree list --porcelain` output
         * Format:
         * worktree /path/to/worktree
         * HEAD abcdef1234567890
         * branch refs/heads/main
         *
         * OR for detached HEAD:
         * worktree /path/to/worktree
         * HEAD abcdef1234567890
         * detached
         */
        fun parseFromPorcelain(lines: List<String>): List<WorktreeInfo> {
            val worktrees = mutableListOf<WorktreeInfo>()
            var currentPath: String? = null
            var currentCommit: String? = null
            var currentBranch: String? = null
            var isMain = false

            for (line in lines) {
                when {
                    line.startsWith("worktree ") -> {
                        currentPath = line.substring("worktree ".length)
                        isMain = false
                    }
                    line.startsWith("HEAD ") -> {
                        currentCommit = line.substring("HEAD ".length)
                    }
                    line.startsWith("branch ") -> {
                        val fullRef = line.substring("branch ".length)
                        currentBranch = fullRef.substringAfterLast('/')
                    }
                    line == "bare" -> {
                        isMain = true
                    }
                    line.isEmpty() -> {
                        // End of worktree entry
                        if (currentPath != null && currentCommit != null) {
                            worktrees.add(
                                WorktreeInfo(
                                    path = currentPath,
                                    commit = currentCommit,
                                    branch = currentBranch,
                                    isMain = isMain
                                )
                            )
                        }
                        currentPath = null
                        currentCommit = null
                        currentBranch = null
                        isMain = false
                    }
                }
            }

            // Handle last entry if file doesn't end with empty line
            if (currentPath != null && currentCommit != null) {
                worktrees.add(
                    WorktreeInfo(
                        path = currentPath,
                        commit = currentCommit,
                        branch = currentBranch,
                        isMain = isMain
                    )
                )
            }

            return worktrees
        }
    }
}
