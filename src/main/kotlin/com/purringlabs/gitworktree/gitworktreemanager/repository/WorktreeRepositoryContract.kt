package com.purringlabs.gitworktree.gitworktreemanager.repository

import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo

interface WorktreeRepositoryContract {
    suspend fun fetchWorktrees(): Result<List<WorktreeInfo>>

    suspend fun createWorktree(name: String, branchName: String): Result<String>

    suspend fun deleteWorktree(worktreePath: String, branchName: String?): Result<Unit>
}
