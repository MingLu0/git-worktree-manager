package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepository
import com.purringlabs.gitworktree.gitworktreemanager.services.FileOperationsService
import com.purringlabs.gitworktree.gitworktreemanager.services.GitWorktreeService
import com.purringlabs.gitworktree.gitworktreemanager.services.IgnoredFilesService
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Paths

/**
 * ViewModel for managing worktree UI state and operations
 * Coordinates between the UI layer and the repository layer
 */
class WorktreeViewModel(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
    private val repository: WorktreeRepository,
    private val ignoredFilesService: IgnoredFilesService,
    private val fileOpsService: FileOperationsService
) {

    var state by mutableStateOf(WorktreeState())
        private set

    /**
     * Refreshes the list of worktrees from the repository
     */
    fun refreshWorktrees() {
        coroutineScope.launch {
            state = state.copy(isLoading = true, error = null)
            repository.fetchWorktrees()
                .onSuccess { worktrees ->
                    state = state.copy(
                        worktrees = worktrees,
                        isLoading = false
                    )
                }
                .onFailure { error ->
                    state = state.copy(
                        error = error.message ?: "Failed to load worktrees",
                        isLoading = false
                    )
                }
        }
    }

    /**
     * Creates a new worktree
     * @param name Name for the worktree directory
     * @param branchName Name of the branch to create/checkout
     * @param onSuccess Callback invoked with the worktree path on success
     * @param onError Callback invoked with error message on failure
     */
    fun createWorktree(
        name: String,
        branchName: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        coroutineScope.launch {
            repository.createWorktree(name, branchName)
                .onSuccess { worktreePath ->
                    refreshWorktrees()
                    onSuccess(worktreePath)
                }
                .onFailure { error ->
                    onError(error.message ?: "Failed to create worktree")
                }
        }
    }

    /**
     * Deletes a worktree
     * @param worktreePath Path to the worktree to delete
     * @param onSuccess Callback invoked on successful deletion
     * @param onError Callback invoked with error message on failure
     */
    fun deleteWorktree(
        worktreePath: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        coroutineScope.launch {
            val branchName = state.worktrees.firstOrNull { it.path == worktreePath }?.branch
            repository.deleteWorktree(worktreePath, branchName)
                .onSuccess {
                    refreshWorktrees()
                    onSuccess()
                }
                .onFailure { error ->
                    onError(error.message ?: "Failed to delete worktree")
                }
        }
    }

    /**
     * Scans the project for files ignored by .gitignore
     * Updates state with the list of ignored files
     */
    suspend fun scanIgnoredFiles(): Result<List<IgnoredFileInfo>> {
        state = state.copy(isScanning = true, scanError = null)
        val result = ignoredFilesService.scanIgnoredFiles(project.basePath ?: "")
        state = state.copy(
            isScanning = false,
            ignoredFiles = result.getOrNull() ?: emptyList(),
            scanError = result.exceptionOrNull()?.message
        )

        return result
    }

    /**
     * Updates the selection state of ignored files
     * @param updatedList New list of ignored files with updated selection states
     */
    fun updateIgnoredFileSelection(updatedList: List<IgnoredFileInfo>) {
        state = state.copy(ignoredFiles = updatedList)
    }

    /**
     * Creates a new worktree and optionally copies selected ignored files
     * @param worktreeName Name for the worktree directory
     * @param branchName Name of the branch to create/checkout
     * @param selectedFiles List of ignored files to copy (only selected items will be copied)
     * @param onSuccess Callback invoked with the worktree path on success
     * @param onError Callback invoked with error message on failure
     */
    fun createWorktreeWithIgnoredFiles(
        worktreeName: String,
        branchName: String,
        selectedFiles: List<IgnoredFileInfo>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        coroutineScope.launch {
            // 1. Create worktree (existing logic)
            repository.createWorktree(worktreeName, branchName)
                .onSuccess { worktreePath ->
                    // 2. If files selected, copy them
                    if (selectedFiles.any { it.selected }) {
                        coroutineScope.launch {
                            copyIgnoredFiles(worktreeName, selectedFiles)
                        }
                    }
                    refreshWorktrees()
                    onSuccess(worktreePath)
                }
                .onFailure { error ->
                    onError(error.message ?: "Failed to create worktree")
                }
        }
    }

    /**
     * Copies selected ignored files to the new worktree
     * Updates state with copy result
     */
    private suspend fun copyIgnoredFiles(
        worktreeName: String,
        selectedFiles: List<IgnoredFileInfo>
    ) {
        val sourceRoot = Paths.get(project.basePath ?: return)

        // Get the Git repository and calculate worktree path
        val gitRepository = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return
        val gitWorktreeService = GitWorktreeService.getInstance(project)
        val destPath = gitWorktreeService.getWorktreePath(gitRepository, worktreeName)
        val destRoot = Paths.get(destPath)

        val result = fileOpsService.copyItems(sourceRoot, destRoot, selectedFiles)
        state = state.copy(copyResult = result)
    }
}
