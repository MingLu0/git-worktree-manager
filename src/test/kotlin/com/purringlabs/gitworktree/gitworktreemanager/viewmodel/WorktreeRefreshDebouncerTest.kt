package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeRefreshDebouncerTest {
    @Test
    fun `debounces multiple refresh requests`() = runBlocking {
        var calls = 0
        val debouncer = WorktreeRefreshDebouncer(this, debounceMs = 50L) { calls++ }

        debouncer.requestRefresh()
        debouncer.requestRefresh()
        debouncer.requestRefresh()

        delay(80L)
        assertEquals(1, calls)
    }

    @Test
    fun `fires again after debounce window`() = runBlocking {
        var calls = 0
        val debouncer = WorktreeRefreshDebouncer(this, debounceMs = 30L) { calls++ }

        debouncer.requestRefresh()
        delay(50L)
        debouncer.requestRefresh()
        delay(50L)

        assertEquals(2, calls)
    }
}
