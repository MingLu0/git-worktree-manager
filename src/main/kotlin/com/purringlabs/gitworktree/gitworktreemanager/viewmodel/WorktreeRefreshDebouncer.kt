package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces refresh requests into a single call after a short delay.
 */
class WorktreeRefreshDebouncer(
    private val coroutineScope: CoroutineScope,
    private val debounceMs: Long = 250L,
    private val onRefresh: () -> Unit
) {
    private var refreshJob: Job? = null

    fun requestRefresh() {
        refreshJob?.cancel()
        refreshJob = coroutineScope.launch {
            delay(debounceMs)
            onRefresh()
        }
    }

    fun cancel() {
        refreshJob?.cancel()
        refreshJob = null
    }
}
