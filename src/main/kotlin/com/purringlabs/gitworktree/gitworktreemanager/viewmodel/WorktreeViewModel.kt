package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ViewModel for managing worktree UI state and operations
 * Coordinates between the UI layer and the repository layer
 */
class WorktreeViewModel(
    private val project: Project,
    private val coroutineScope: CoroutineScope
) {
    private val repository = WorktreeRepository(project)

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
            repository.deleteWorktree(worktreePath)
                .onSuccess {
                    refreshWorktrees()
                    onSuccess()
                }
                .onFailure { error ->
                    onError(error.message ?: "Failed to delete worktree")
                }
        }
    }
}
