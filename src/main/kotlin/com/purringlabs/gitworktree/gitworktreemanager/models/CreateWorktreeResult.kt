package com.purringlabs.gitworktree.gitworktreemanager.models

/**
 * Result of attempting to create a worktree.
 *
 * @property path Absolute path of the worktree.
 * @property created True if we created a new worktree; false if it already existed and we reused it.
 */
data class CreateWorktreeResult(
    val path: String,
    val created: Boolean
)
