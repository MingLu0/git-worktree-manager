package com.purringlabs.gitworktree.gitworktreemanager

import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class MyToolWindowOpenWorktreeUnitTest {

    @Test
    fun `isWorktreeAlreadyOpen returns true when canonical paths match`() {
        val openBasePaths = sequenceOf(
            "/Users/ming/repo",
            "/Users/ming/repo/./",
            null
        )

        assertTrue(isWorktreeAlreadyOpen(openBasePaths, "/Users/ming/repo"))
    }

    @Test
    fun `isWorktreeAlreadyOpen returns false when no project matches`() {
        val openBasePaths = sequenceOf(
            "/Users/ming/repo-a",
            "/Users/ming/repo-b"
        )

        assertFalse(isWorktreeAlreadyOpen(openBasePaths, "/Users/ming/repo-c"))
    }

    @Test
    fun `restoreFromMinimizedPreservingMaximized clears only ICONIFIED bit`() {
        val maximizedAndMinimized = Frame.MAXIMIZED_BOTH or Frame.ICONIFIED
        val restored = restoreFromMinimizedPreservingMaximized(maximizedAndMinimized)

        // still maximized
        assertEquals(Frame.MAXIMIZED_BOTH, restored and Frame.MAXIMIZED_BOTH)
        // no longer minimized
        assertEquals(0, restored and Frame.ICONIFIED)
    }
}
