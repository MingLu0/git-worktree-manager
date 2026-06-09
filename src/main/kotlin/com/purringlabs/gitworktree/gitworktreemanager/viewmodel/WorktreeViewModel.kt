package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyOption
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyResult
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepositoryContract
import com.purringlabs.gitworktree.gitworktreemanager.services.ClaudeCodeContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ViewModel for managing worktree UI state and operations
 * Coordinates between the UI layer and the repository layer
 */
class WorktreeViewModel(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
    private val repository: WorktreeRepositoryContract,
    private val claudeCodeContextService: ClaudeCodeContextService
) {

    var state by mutableStateOf(WorktreeState())
        internal set
    private val autoRefreshDebouncer = WorktreeRefreshDebouncer(coroutineScope) { refreshWorktrees() }

    fun setSearchQuery(query: String) {
        state = state.copy(searchQuery = query)
    }

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
     * Requests a debounced refresh for repository change events.
     */
    fun requestAutoRefresh() {
        autoRefreshDebouncer.requestRefresh()
    }

    /**
     * Cancels any pending debounced refresh when disposing.
     */
    fun cancelAutoRefresh() {
        autoRefreshDebouncer.cancel()
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
        createNewBranch: Boolean = true,
        onSuccess: (com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        coroutineScope.launch {
            state = state.copy(isCreating = true, error = null)
            try {
                repository.createWorktree(name, branchName, createNewBranch)
                    .onSuccess { result ->
                        refreshWorktrees()
                        onSuccess(result)
                    }
                    .onFailure { error ->
                        onError(error)
                    }
            } finally {
                state = state.copy(isCreating = false)
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
        onSuccess: (com.purringlabs.gitworktree.gitworktreemanager.models.DeleteWorktreeResult) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        coroutineScope.launch {
            state = state.copy(deletingWorktreePath = worktreePath, error = null)
            try {
                val worktree = state.worktrees.firstOrNull { it.path == worktreePath }
                    ?: return@launch onError(IllegalStateException("Worktree not found in current state: $worktreePath"))

                if (worktree.isLocked) {
                    return@launch onError(
                        IllegalStateException(
                            buildString {
                                append("Cannot delete locked worktree")
                                worktree.lockReason?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                            }
                        )
                    )
                }

                if (worktree.isPrunable) {
                    return@launch onError(
                        IllegalStateException(
                            buildString {
                                append("Worktree metadata is stale/prunable. Run 'git worktree prune' first")
                                worktree.prunableReason?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                            }
                        )
                    )
                }

                val branchName = worktree.branch
                repository.deleteWorktree(worktreePath, branchName)
                    .onSuccess { result ->
                        refreshWorktrees()
                        onSuccess(result)
                    }
                    .onFailure { error ->
                        onError(error)
                    }
            } finally {
                state = state.copy(deletingWorktreePath = null)
            }
        }
    }

    /**
     * Creates a new worktree and optionally copies selected Claude Code context.
     * @param worktreeName Name for the worktree directory
     * @param branchName Name of the branch to create/checkout
     * @param selectedOptions Claude context options to copy
     * @param onSuccess Callback invoked with the worktree path and copy result on success
     * @param onError Callback invoked with error message on failure
     */
    fun createWorktreeWithAgentContext(
        worktreeName: String,
        branchName: String,
        createNewBranch: Boolean = true,
        selectedOptions: List<AgentContextCopyOption>,
        onSuccess: (com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult, AgentContextCopyResult?) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        coroutineScope.launch {
            state = state.copy(isCreating = true, error = null)
            try {
                repository.createWorktree(worktreeName, branchName, createNewBranch)
                    .onSuccess { result ->
                        val copyResult = if (selectedOptions.any { it.selected }) {
                            claudeCodeContextService.copySelectedOptions(selectedOptions)
                        } else {
                            null
                        }
                        refreshWorktrees()
                        onSuccess(result, copyResult)
                    }
                    .onFailure { error ->
                        onError(error)
                    }
            } finally {
                state = state.copy(isCreating = false)
            }
        }
    }
}
