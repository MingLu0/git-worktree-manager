package com.purringlabs.gitworktree.gitworktreemanager.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeInfoParsePorcelainTest {

    @Test
    fun `parseFromPorcelain captures locked and prunable metadata`() {
        val parsed = WorktreeInfo.parseFromPorcelain(
            listOf(
                "worktree /repo",
                "HEAD abc123",
                "branch refs/heads/main",
                "",
                "worktree /repo-feature",
                "HEAD def456",
                "branch refs/heads/feature/test",
                "locked manually locked",
                "prunable gitdir file points to non-existent location",
                ""
            )
        )

        assertEquals(2, parsed.size)

        val main = parsed[0]
        assertEquals("/repo", main.path)
        assertFalse(main.isLocked)
        assertFalse(main.isPrunable)

        val broken = parsed[1]
        assertEquals("/repo-feature", broken.path)
        assertEquals("feature/test", broken.branch)
        assertTrue(broken.isLocked)
        assertTrue(broken.isPrunable)
        assertEquals("manually locked", broken.lockReason)
        assertEquals("gitdir file points to non-existent location", broken.prunableReason)
    }
}
