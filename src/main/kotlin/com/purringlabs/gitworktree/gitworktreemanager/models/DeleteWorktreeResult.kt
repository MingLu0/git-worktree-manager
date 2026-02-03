package com.purringlabs.gitworktree.gitworktreemanager.models

data class DeleteWorktreeResult(
    val worktreeRemoved: Boolean,
    val branchDeleted: Boolean
)
