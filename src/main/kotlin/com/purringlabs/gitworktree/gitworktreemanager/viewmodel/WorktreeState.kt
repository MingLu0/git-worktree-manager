package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import com.purringlabs.gitworktree.gitworktreemanager.models.CopyResult
import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo

/**
 * Represents the UI state for the worktree list and ignored files workflow
 */
data class WorktreeState(
    val worktrees: List<WorktreeInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,

    // Ignored files workflow state
    val copyIgnoredFilesEnabled: Boolean = false,
    val ignoredFiles: List<IgnoredFileInfo> = emptyList(),
    val isScanning: Boolean = false,
    val scanError: String? = null,
    val copyResult: CopyResult? = null
)
