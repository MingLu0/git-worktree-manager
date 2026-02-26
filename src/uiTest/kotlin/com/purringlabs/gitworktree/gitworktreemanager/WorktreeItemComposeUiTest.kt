package com.purringlabs.gitworktree.gitworktreemanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import kotlin.test.Test
import org.junit.Assume.assumeTrue

@OptIn(ExperimentalTestApi::class)
class WorktreeItemComposeUiTest {

    private fun assumeUiTestsEnabled() {
        assumeTrue(
            "Set -DenableDesktopComposeUiTests=true to run Compose Desktop UI tests",
            System.getProperty("enableDesktopComposeUiTests") == "true"
        )
    }

    @Test
    fun `delete button is disabled for main worktree`() {
        val wt = WorktreeInfo(
            path = "/tmp/main",
            commit = "0123456789abcdef",
            branch = "main",
            isMain = true
        )

        assumeUiTestsEnabled()

        runComposeUiTest {
            setContent {
                WorktreeItem(
                    worktree = wt,
                    isCurrent = false,
                    isDeleting = false,
                    onOpen = {},
                    onDelete = {}
                )
            }

            onNodeWithTag("deleteButton:${wt.path}")
                .assertIsDisplayed()
                .assertIsNotEnabled()

            // Main label text is present
            onNodeWithText("Main working tree (repo root)").assertIsDisplayed()
        }
    }

    @Test
    fun `delete button is enabled for non-main worktree when not deleting`() {
        val wt = WorktreeInfo(
            path = "/tmp/feature",
            commit = "0123456789abcdef",
            branch = "feature/foo",
            isMain = false
        )

        assumeUiTestsEnabled()

        runComposeUiTest {
            setContent {
                WorktreeItem(
                    worktree = wt,
                    isCurrent = false,
                    isDeleting = false,
                    onOpen = {},
                    onDelete = {}
                )
            }

            onNodeWithTag("deleteButton:${wt.path}")
                .assertIsDisplayed()
                .assertIsEnabled()
        }
    }

    @Test
    fun `current worktree is tagged as current`() {
        val wt = WorktreeInfo(
            path = "/tmp/current",
            commit = "0123456789abcdef",
            branch = "main",
            isMain = false
        )

        assumeUiTestsEnabled()

        runComposeUiTest {
            setContent {
                WorktreeItem(
                    worktree = wt,
                    isCurrent = true,
                    isDeleting = false,
                    onOpen = {},
                    onDelete = {}
                )
            }

            onNodeWithText("(current)").assertIsDisplayed()
        }
    }
}
