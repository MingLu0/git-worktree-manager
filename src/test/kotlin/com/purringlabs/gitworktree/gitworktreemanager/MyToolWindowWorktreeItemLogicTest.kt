package com.purringlabs.gitworktree.gitworktreemanager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MyToolWindowWorktreeItemLogicTest {

    @Test
    fun `isCurrentWorktree returns true when canonical paths match`() {
        assertTrue(
            isCurrentWorktree(
                currentProjectBasePath = "/Users/ming/repo/./",
                worktreePath = "/Users/ming/repo"
            )
        )
    }

    @Test
    fun `isCurrentWorktree returns false when current project path is null`() {
        assertFalse(isCurrentWorktree(currentProjectBasePath = null, worktreePath = "/Users/ming/repo"))
    }

    @Test
    fun `isCurrentWorktree returns false when paths differ`() {
        assertFalse(
            isCurrentWorktree(
                currentProjectBasePath = "/Users/ming/repo-a",
                worktreePath = "/Users/ming/repo-b"
            )
        )
    }

    @Test
    fun `isDeleteEnabled returns false for main worktree`() {
        assertFalse(isDeleteEnabled(isMain = true, isDeleting = false))
    }

    @Test
    fun `isDeleteEnabled returns false while deleting`() {
        assertFalse(isDeleteEnabled(isMain = false, isDeleting = true))
    }

    @Test
    fun `isDeleteEnabled returns true for non-main and not deleting`() {
        assertTrue(isDeleteEnabled(isMain = false, isDeleting = false))
    }
}
