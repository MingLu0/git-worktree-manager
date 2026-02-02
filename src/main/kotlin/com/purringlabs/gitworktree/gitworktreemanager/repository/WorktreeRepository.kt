package com.purringlabs.gitworktree.gitworktreemanager.repository

import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.services.GitWorktreeService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository layer for worktree operations
 * Handles data access and wraps GitWorktreeService
 */
class WorktreeRepository(private val project: Project) {

    private val service: GitWorktreeService
        get() = GitWorktreeService.getInstance(project)

    private val currentRepository: GitRepository?
        get() = GitRepositoryManager.getInstance(project).repositories.firstOrNull()

    /**
     * Fetches the list of worktrees
     * @return Result containing list of WorktreeInfo or error
     */
    suspend fun fetchWorktrees(): Result<List<WorktreeInfo>> = withContext(Dispatchers.IO) {
        val repository = currentRepository
            ?: return@withContext Result.failure(NoRepositoryException("No Git repository found in project"))

        runCatching {
            service.listWorktrees(repository)
        }
    }

    /**
     * Creates a new worktree
     * @param name Name for the worktree directory
     * @param branchName Name of the branch to create/checkout
     * @return Result indicating success or failure
     */
    suspend fun createWorktree(name: String, branchName: String): Result<String> = withContext(Dispatchers.IO) {
        val repository = currentRepository
            ?: return@withContext Result.failure(NoRepositoryException("No Git repository found in project"))

        runCatching {
            val success = service.createWorktree(repository, name, branchName)
            if (success) {
                service.getWorktreePath(repository, name)
            } else {
                throw WorktreeOperationException("Failed to create worktree")
            }
        }
    }

    /**
     * Deletes a worktree
     * @param worktreePath Path to the worktree to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteWorktree(worktreePath: String, branchName: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val repository = currentRepository
            ?: return@withContext Result.failure(NoRepositoryException("No Git repository found in project"))

        runCatching {
            val success = service.deleteWorktree(repository, worktreePath, branchName)
            if (success) {
                Unit
            } else {
                throw WorktreeOperationException("Failed to delete worktree")
            }
        }
    }
}

/**
 * Exception thrown when no Git repository is found
 */
class NoRepositoryException(message: String) : Exception(message)

/**
 * Exception thrown when a worktree operation fails
 */
class WorktreeOperationException(message: String) : Exception(message)
