package com.purringlabs.gitworktree.gitworktreemanager.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiErrorMapperWorktreeStateTest {

    @Test
    fun `maps locked worktree delete message`() {
        val ui = UiErrorMapper.map(
            IllegalStateException("Cannot delete locked worktree: portable disk"),
            operation = "DELETE_WORKTREE"
        )

        assertEquals("Git Worktree Manager — Worktree is locked", ui.title)
        assertTrue(ui.summary.contains("locked"))
        assertTrue(ui.actions.any { it.contains("git worktree unlock") })
    }

    @Test
    fun `maps prunable worktree delete message`() {
        val ui = UiErrorMapper.map(
            IllegalStateException("Worktree metadata is stale/prunable. Run 'git worktree prune' first: gitdir file points to non-existent location"),
            operation = "DELETE_WORKTREE"
        )

        assertEquals("Git Worktree Manager — Worktree metadata is stale", ui.title)
        assertTrue(ui.summary.contains("stale/broken"))
        assertTrue(ui.actions.any { it.contains("git worktree prune") })
    }
}
