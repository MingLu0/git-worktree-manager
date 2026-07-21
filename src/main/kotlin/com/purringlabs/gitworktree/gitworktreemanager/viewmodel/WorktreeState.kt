package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import com.purringlabs.gitworktree.gitworktreemanager.models.ClaudeSessionInfo
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo

/**
 * Represents the UI state for the worktree list.
 */
data class WorktreeState(
    val worktrees: List<WorktreeInfo> = emptyList(),
    val sessions: List<ClaudeSessionInfo> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val isLoadingSessions: Boolean = false,
    val isCreating: Boolean = false,
    val deletingWorktreePath: String? = null,
    val error: String? = null,
    val sessionsError: String? = null
)
