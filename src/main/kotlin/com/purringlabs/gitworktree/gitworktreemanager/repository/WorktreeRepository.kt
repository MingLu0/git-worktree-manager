package com.purringlabs.gitworktree.gitworktreemanager.repository

import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.exceptions.NoRepositoryException
import com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult
import com.purringlabs.gitworktree.gitworktreemanager.models.DeleteWorktreeEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.ListWorktreesEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.services.GitWorktreeService
import com.purringlabs.gitworktree.gitworktreemanager.services.TelemetryErrorMapper
import com.purringlabs.gitworktree.gitworktreemanager.services.TelemetryService
import com.purringlabs.gitworktree.gitworktreemanager.services.TelemetryServiceImpl
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Repository layer for worktree operations
 * Handles data access and wraps GitWorktreeService
 */
class WorktreeRepository(private val project: Project) : WorktreeRepositoryContract {

    private val service: GitWorktreeService
        get() = GitWorktreeService.getInstance(project)

    private val telemetryService: TelemetryService
        get() = TelemetryServiceImpl.getInstance()

    private val currentRepository: GitRepository?
        get() = GitRepositoryManager.getInstance(project)
            .repositories
            .firstOrNull { repo -> File(repo.root.path).exists() }

    /**
     * Fetches the list of worktrees
     * @return Result containing list of WorktreeInfo or error
     */
    override suspend fun fetchWorktrees(): Result<List<WorktreeInfo>> = withContext(Dispatchers.IO) {
        val operationId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val repository = currentRepository
        val result = if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.listWorktrees(repository)
        }

        telemetryService.recordOperation(
            ListWorktreesEvent(
                operationId = operationId,
                startTime = startTime,
                durationMs = System.currentTimeMillis() - startTime,
                success = result.isSuccess,
                context = telemetryService.getContext(),
                worktreeCount = result.getOrNull()?.size ?: 0,
                error = TelemetryErrorMapper.mapToStructuredError(result.exceptionOrNull())
            )
        )

        result
    }

    /**
     * Creates a new worktree
     * @param name Name for the worktree directory
     * @param branchName Name of the branch to create/checkout
     * @return Result indicating success or failure
     */
    override suspend fun createWorktree(
        name: String,
        branchName: String,
        createNewBranch: Boolean
    ): Result<CreateWorktreeResult> = withContext(Dispatchers.IO) {
        val operationId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val repository = currentRepository
        val result: Result<CreateWorktreeResult> = if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.createWorktree(repository, name, branchName, createNewBranch)
        }

        telemetryService.recordOperation(
            CreateWorktreeEvent(
                operationId = operationId,
                startTime = startTime,
                durationMs = System.currentTimeMillis() - startTime,
                success = result.isSuccess,
                context = telemetryService.getContext(),
                worktreeName = name,
                branchName = branchName,
                error = TelemetryErrorMapper.mapToStructuredError(result.exceptionOrNull())
            )
        )

        result
    }

    /**
     * Deletes a worktree
     * @param worktreePath Path to the worktree to delete
     * @return Result indicating success or failure
     */
    override suspend fun deleteWorktree(worktreePath: String, branchName: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val operationId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val repository = currentRepository
        val result = if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.deleteWorktree(repository, worktreePath, branchName)
        }

        telemetryService.recordOperation(
            DeleteWorktreeEvent(
                operationId = operationId,
                startTime = startTime,
                durationMs = System.currentTimeMillis() - startTime,
                success = result.isSuccess,
                context = telemetryService.getContext(),
                worktreeName = worktreePath.substringAfterLast(File.separatorChar),
                branchDeleted = result.getOrNull()?.branchDeleted ?: false,
                error = TelemetryErrorMapper.mapToStructuredError(result.exceptionOrNull())
            )
        )

        result.map { Unit }
    }
}

