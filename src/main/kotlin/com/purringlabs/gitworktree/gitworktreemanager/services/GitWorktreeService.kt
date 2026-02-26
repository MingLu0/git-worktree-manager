package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.purringlabs.gitworktree.gitworktreemanager.exceptions.WorktreeOperationException
import com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult
import com.purringlabs.gitworktree.gitworktreemanager.models.DeleteWorktreeResult
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.annotations.VisibleForTesting
import java.io.File

@Service(Service.Level.PROJECT)
class GitWorktreeService(private val project: Project) {
    private val logger = Logger.getInstance(GitWorktreeService::class.java)

    /**
     * Creates a new git worktree
     * @param repository The git repository
     * @param worktreeName Name for the worktree directory
     * @param branchName Name of the branch to create/checkout
     * @return Result containing the worktree path or error details
     */
    fun createWorktree(
        repository: GitRepository,
        worktreeName: String,
        branchName: String,
        createNewBranch: Boolean = true
    ): Result<CreateWorktreeResult> {
        val git = Git.getInstance()
        val worktreePath = getWorktreePath(repository, worktreeName)

        // Fast path: if the directory exists and is already registered as a worktree,
        // treat this as success and let the caller open/focus it.
        val existingDir = File(worktreePath)
        if (existingDir.exists()) {
            val existing = listWorktrees(repository)
                .getOrNull()
                ?.firstOrNull { it.path == worktreePath }
            if (existing != null) {
                return Result.success(CreateWorktreeResult(path = worktreePath, created = false))
            }
        }

        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("add")
        handler.addParameters(worktreePath)
        if (createNewBranch) {
            handler.addParameters("-b", branchName)
        } else {
            // Use existing branch
            handler.addParameters(branchName)
        }

        return try {
            val result = git.runCommand(handler)
            if (result.success()) {
                Result.success(CreateWorktreeResult(path = worktreePath, created = true))
            } else {
                val err = result.errorOutputAsJoinedString

                // If Git says it already exists, try to recover by listing worktrees and returning the existing one.
                if (err.lowercase().contains("already exists")) {
                    val existing = listWorktrees(repository)
                        .getOrNull()
                        ?.firstOrNull { it.path == worktreePath }
                    if (existing != null) {
                        return Result.success(CreateWorktreeResult(path = worktreePath, created = false))
                    }
                }

                Result.failure(
                    WorktreeOperationException(
                        message = "Failed to create worktree '$worktreeName'",
                        gitCommand = handler.printableCommandLine(),
                        gitExitCode = result.exitCode,
                        gitErrorOutput = err
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(
                WorktreeOperationException(
                    message = "Unexpected error creating worktree: ${e.message}",
                    cause = e
                )
            )
        }
    }

    /**
     * Lists all worktrees for the repository
     * @param repository The git repository
     * @return Result containing list of WorktreeInfo objects or error details
     */
    fun listWorktrees(repository: GitRepository): Result<List<WorktreeInfo>> {
        val git = Git.getInstance()
        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("list")
        handler.addParameters("--porcelain")

        return try {
            val result = git.runCommand(handler)
            if (result.success()) {
                val parsed = WorktreeInfo.parseFromPorcelain(result.output)

                // Git's porcelain output doesn't explicitly mark the primary (main) working tree.
                // A practical and reliable heuristic is: main worktree has a real .git directory;
                // linked worktrees have a .git file that points at the shared gitdir.
                val withMainFlag = parsed.map { wt ->
                    wt.copy(isMain = isMainWorktreePath(wt.path))
                }

                Result.success(withMainFlag)
            } else {
                Result.failure(
                    WorktreeOperationException(
                        message = "Failed to list worktrees",
                        gitCommand = handler.printableCommandLine(),
                        gitExitCode = result.exitCode,
                        gitErrorOutput = result.errorOutputAsJoinedString
                    )
                )
            }
        } catch (e: ProcessCanceledException) {
            // IntelliJ cancellation must always propagate.
            throw e
        } catch (e: Exception) {
            Result.failure(
                WorktreeOperationException(
                    message = "Error parsing worktree list: ${e.message}",
                    cause = e
                )
            )
        }
    }

    /**
     * Deletes a worktree
     * @param repository The git repository
     * @param worktreePath Path to the worktree to delete
     * @return Result containing deletion outcomes or error details
     */
    fun deleteWorktree(
        repository: GitRepository,
        worktreePath: String,
        branchName: String?
    ): Result<DeleteWorktreeResult> {
        val git = Git.getInstance()
        val worktreeHandler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        worktreeHandler.addParameters("remove")
        worktreeHandler.addParameters(worktreePath)
        worktreeHandler.addParameters("--force")

        return try {
            val worktreeResult = git.runCommand(worktreeHandler)
            if (!worktreeResult.success()) {
                return Result.failure(
                    WorktreeOperationException(
                        message = "Failed to remove worktree '$worktreePath'",
                        gitCommand = worktreeHandler.printableCommandLine(),
                        gitExitCode = worktreeResult.exitCode,
                        gitErrorOutput = worktreeResult.errorOutputAsJoinedString
                    )
                )
            }

            var branchDeleted = false
            if (!branchName.isNullOrBlank()) {
                val deleteBranchHandler = GitLineHandler(project, repository.root, GitCommand.BRANCH)
                deleteBranchHandler.addParameters("-D", branchName)
                val branchResult = git.runCommand(deleteBranchHandler)
                branchDeleted = branchResult.success()
                if (!branchDeleted) {
                    logger.warn(
                        "Worktree '$worktreePath' removed, but failed to delete branch '$branchName': " +
                            branchResult.errorOutputAsJoinedString
                    )
                }
            }

            Result.success(DeleteWorktreeResult(worktreeRemoved = true, branchDeleted = branchDeleted))
        } catch (e: Exception) {
            Result.failure(
                WorktreeOperationException(
                    message = "Unexpected error deleting worktree: ${e.message}",
                    cause = e
                )
            )
        }
    }

    /**
     * Calculates the path where a worktree should be created
     * Creates worktrees in the parent directory following the pattern: ../project-name-worktree-name
     * @param repository The git repository
     * @param worktreeName The name for the worktree
     * @return The absolute path for the worktree
     */
    fun branchExists(repository: GitRepository, branchName: String): Boolean {
        val git = Git.getInstance()
        val handler = GitLineHandler(project, repository.root, GitCommand.BRANCH)
        handler.addParameters("--list", branchName)

        return try {
            val result = git.runCommand(handler)
            if (!result.success()) return false
            result.output.any { it.isNotBlank() }
        } catch (_: Exception) {
            false
        }
    }

    fun getWorktreePath(repository: GitRepository, worktreeName: String): String {
        val projectDir = File(repository.root.path)
        val projectName = projectDir.name
        val parentDir = projectDir.parentFile
        return File(parentDir, "$projectName-$worktreeName").absolutePath
    }

    @VisibleForTesting
    internal fun isMainWorktreePath(worktreePath: String): Boolean {
        // Resolve weirdness like /a/b/./c
        val canonical = FileUtil.toCanonicalPath(worktreePath)
        val gitPath = File(canonical, ".git")
        return gitPath.isDirectory
    }

    companion object {
        fun getInstance(project: Project): GitWorktreeService {
            return project.getService(GitWorktreeService::class.java)
        }
    }
}
