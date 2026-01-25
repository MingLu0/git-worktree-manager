package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.io.File

@Service(Service.Level.PROJECT)
class GitWorktreeService(private val project: Project) {

    /**
     * Creates a new git worktree
     * @param repository The git repository
     * @param worktreeName Name for the worktree directory
     * @param branchName Name of the branch to create/checkout
     * @return true if successful, false otherwise
     */
    fun createWorktree(
        repository: GitRepository,
        worktreeName: String,
        branchName: String
    ): Boolean {
        val git = Git.getInstance()
        val worktreePath = getWorktreePath(repository, worktreeName)

        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("add")
        handler.addParameters(worktreePath)
        handler.addParameters("-b", branchName)

        val result = git.runCommand(handler)
        return result.success()
    }

    /**
     * Lists all worktrees for the repository
     * @param repository The git repository
     * @return List of WorktreeInfo objects
     */
    fun listWorktrees(repository: GitRepository): List<WorktreeInfo> {
        val git = Git.getInstance()
        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("list")
        handler.addParameters("--porcelain")

        val result = git.runCommand(handler)
        return if (result.success()) {
            WorktreeInfo.parseFromPorcelain(result.output)
        } else {
            emptyList()
        }
    }

    /**
     * Deletes a worktree
     * @param repository The git repository
     * @param worktreePath Path to the worktree to delete
     * @return true if successful, false otherwise
     */
    fun deleteWorktree(repository: GitRepository, worktreePath: String): Boolean {
        val git = Git.getInstance()
        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("remove")
        handler.addParameters(worktreePath)
        handler.addParameters("--force")

        val result = git.runCommand(handler)
        return result.success()
    }

    /**
     * Calculates the path where a worktree should be created
     * Creates worktrees in the parent directory following the pattern: ../project-name-worktree-name
     * @param repository The git repository
     * @param worktreeName The name for the worktree
     * @return The absolute path for the worktree
     */
    fun getWorktreePath(repository: GitRepository, worktreeName: String): String {
        val projectDir = File(repository.root.path)
        val projectName = projectDir.name
        val parentDir = projectDir.parentFile
        return File(parentDir, "$projectName-$worktreeName").absolutePath
    }

    companion object {
        fun getInstance(project: Project): GitWorktreeService {
            return project.getService(GitWorktreeService::class.java)
        }
    }
}
