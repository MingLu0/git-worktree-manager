package com.purringlabs.gitworktree.gitworktreemanager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MyToolWindowWorktreeItemLogicTest {

    private fun wt(path: String, branch: String? = null, isMain: Boolean = false) =
        com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo(
            path = path,
            commit = "deadbeef",
            branch = branch,
            isMain = isMain
        )

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
        assertFalse(isDeleteEnabled(isMain = true, isCurrent = false, isDeleting = false))
    }

    @Test
    fun `isDeleteEnabled returns false for current worktree`() {
        assertFalse(isDeleteEnabled(isMain = false, isCurrent = true, isDeleting = false))
    }

    @Test
    fun `isDeleteEnabled returns false while deleting`() {
        assertFalse(isDeleteEnabled(isMain = false, isCurrent = false, isDeleting = true))
    }

    @Test
    fun `isDeleteEnabled returns true for non-main, non-current, and not deleting`() {
        assertTrue(isDeleteEnabled(isMain = false, isCurrent = false, isDeleting = false))
    }

    @Test
    fun `sortWorktreesForDisplay pins current then main then others`() {
        val currentPath = "/Users/ming/repo"

        val main = wt(path = "/Users/ming/repo-main", branch = "master", isMain = true)
        val current = wt(path = currentPath, branch = "feature", isMain = false)
        val otherA = wt(path = "/Users/ming/repo-wt-a", branch = "a")
        val otherB = wt(path = "/Users/ming/repo-wt-b", branch = "b")

        val sorted = sortWorktreesForDisplay(
            worktrees = listOf(otherB, main, otherA, current),
            currentProjectBasePath = currentPath
        )

        // Current is first
        assertTrue(sorted.first().path == currentPath)
        // Main is second
        assertTrue(sorted[1].isMain)
        // The rest are stable-sorted by branch then path (a then b)
        assertTrue(sorted[2].branch == "a")
        assertTrue(sorted[3].branch == "b")
    }

    @Test
    fun `sortWorktreesForDisplay pins main first when no current`() {
        val main = wt(path = "/repo", branch = "master", isMain = true)
        val other = wt(path = "/repo-wt", branch = "feature")

        val sorted = sortWorktreesForDisplay(
            worktrees = listOf(other, main),
            currentProjectBasePath = null
        )

        assertTrue(sorted.first().isMain)
    }
}
