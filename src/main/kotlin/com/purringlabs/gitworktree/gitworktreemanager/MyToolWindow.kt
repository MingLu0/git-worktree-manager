package com.purringlabs.gitworktree.gitworktreemanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import java.io.File
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab("Git Worktrees", focusOnClickInside = true) {
            WorktreeManagerContent(project)
        }
    }
}

/**
 * Wrapper composable that holds the Project reference and manages the ViewModel
 * This is the only composable that knows about Project and IntelliJ Platform APIs
 */
@Composable
private fun WorktreeManagerContent(project: Project) {
    val coroutineScope = rememberCoroutineScope()
    val viewModel = remember { WorktreeViewModel(project, coroutineScope) }

    // Initialize data on first composition
    LaunchedEffect(Unit) {
        viewModel.refreshWorktrees()
    }

    WorktreeListContent(
        state = viewModel.state,
        onRefresh = {
            viewModel.refreshWorktrees()
        },
        onCreateWorktree = { name, branch ->
            viewModel.createWorktree(
                name = name,
                branchName = branch,
                onSuccess = { worktreePath ->
                    coroutineScope.launch(Dispatchers.Main) {
                        // Open the worktree in a new window
                        ProjectUtil.openOrImport(File(worktreePath).toPath(), project, true)
                        Messages.showInfoMessage(
                            project,
                            "Worktree created and opened in new window!",
                            "Success"
                        )
                    }
                },
                onError = { errorMessage ->
                    coroutineScope.launch(Dispatchers.Main) {
                        Messages.showErrorDialog(
                            project,
                            errorMessage,
                            "Error"
                        )
                    }
                }
            )
        },
        onDeleteWorktree = { worktree ->
            viewModel.deleteWorktree(
                worktreePath = worktree.path,
                onSuccess = {
                    coroutineScope.launch(Dispatchers.Main) {
                        Messages.showInfoMessage(
                            project,
                            "Worktree deleted successfully!",
                            "Success"
                        )
                    }
                },
                onError = { errorMessage ->
                    coroutineScope.launch(Dispatchers.Main) {
                        Messages.showErrorDialog(
                            project,
                            errorMessage,
                            "Error"
                        )
                    }
                }
            )
        },
        onRequestWorktreeName = {
            Messages.showInputDialog(
                project,
                "Enter worktree name:",
                "Create Worktree",
                null
            )
        },
        onRequestBranchName = { defaultName ->
            Messages.showInputDialog(
                project,
                "Enter branch name:",
                "Create Worktree",
                null,
                defaultName,
                null
            )
        },
        onConfirmDelete = { worktree ->
            val result = Messages.showYesNoDialog(
                project,
                "Are you sure you want to delete this worktree?\n${worktree.path}",
                "Delete Worktree",
                Messages.getWarningIcon()
            )
            result == Messages.YES
        }
    )
}

/**
 * Pure UI composable for displaying the worktree list
 * No dependency on Project - can be previewed with mock data
 */
@Composable
private fun WorktreeListContent(
    state: com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeState,
    onRefresh: () -> Unit,
    onCreateWorktree: (name: String, branch: String) -> Unit,
    onDeleteWorktree: (WorktreeInfo) -> Unit,
    onRequestWorktreeName: () -> String?,
    onRequestBranchName: (defaultName: String) -> String?,
    onConfirmDelete: (WorktreeInfo) -> Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Create button at the top
        OutlinedButton(onClick = {
            val worktreeName = onRequestWorktreeName()
            if (!worktreeName.isNullOrBlank()) {
                val branchName = onRequestBranchName(worktreeName)
                if (!branchName.isNullOrBlank()) {
                    onCreateWorktree(worktreeName, branchName)
                }
            }
        }) {
            Text("Create Worktree")
        }

        // Error message
        state.error?.let { error ->
            Text(
                text = error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(8.dp)
            )
        }

        // Worktree list
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading worktrees...")
            }
        } else if (state.worktrees.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No worktrees found")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.worktrees) { worktree ->
                    WorktreeItem(
                        worktree = worktree,
                        onDelete = {
                            if (onConfirmDelete(worktree)) {
                                onDeleteWorktree(worktree)
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Pure UI composable for displaying a single worktree item
 * No dependency on Project - can be previewed with mock data
 */
@Composable
private fun WorktreeItem(
    worktree: WorktreeInfo,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = worktree.branch ?: "detached HEAD",
                    fontWeight = FontWeight.Bold
                )
                if (worktree.isMain) {
                    Text(text = "(main)", fontWeight = FontWeight.Light)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = worktree.path,
                fontWeight = FontWeight.Light
            )
            Text(
                text = "Commit: ${worktree.commit.take(8)}",
                fontWeight = FontWeight.Light
            )
        }

        // Delete button (only show for non-main worktrees)
        if (!worktree.isMain) {
            OutlinedButton(onClick = onDelete) {
                Text("Delete")
            }
        }
    }
}
