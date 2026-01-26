package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo

/**
 * Represents the UI state for the worktree list
 */
data class WorktreeState(
    val worktrees: List<WorktreeInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
