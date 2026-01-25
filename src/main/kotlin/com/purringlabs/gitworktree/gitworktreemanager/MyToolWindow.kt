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
import com.purringlabs.gitworktree.gitworktreemanager.services.GitWorktreeService
import git4idea.repo.GitRepositoryManager
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
            WorktreeListContent(project)
        }
    }
}

@Composable
private fun WorktreeListContent(project: Project) {
    var worktrees by remember { mutableStateOf<List<WorktreeInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    fun refreshWorktrees() {
        coroutineScope.launch(Dispatchers.IO) {
            isLoading = true
            val repositoryManager = GitRepositoryManager.getInstance(project)
            val repository = repositoryManager.repositories.firstOrNull()

            if (repository != null) {
                val service = GitWorktreeService.getInstance(project)
                worktrees = service.listWorktrees(repository)
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshWorktrees()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Create button at the top
        OutlinedButton(onClick = {
            val worktreeName = Messages.showInputDialog(
                project,
                "Enter worktree name:",
                "Create Worktree",
                null
            )
            if (!worktreeName.isNullOrBlank()) {
                val branchName = Messages.showInputDialog(
                    project,
                    "Enter branch name:",
                    "Create Worktree",
                    null,
                    worktreeName,
                    null
                )
                if (!branchName.isNullOrBlank()) {
                    coroutineScope.launch(Dispatchers.IO) {
                        val repositoryManager = GitRepositoryManager.getInstance(project)
                        val repository = repositoryManager.repositories.firstOrNull()

                        if (repository != null) {
                            val service = GitWorktreeService.getInstance(project)
                            val success = service.createWorktree(repository, worktreeName, branchName)

                            withContext(Dispatchers.Main) {
                                if (success) {
                                    // Open the worktree in a new window
                                    val worktreePath = service.getWorktreePath(repository, worktreeName)
                                    ProjectUtil.openOrImport(File(worktreePath).toPath(), project, true)

                                    Messages.showInfoMessage(
                                        project,
                                        "Worktree created and opened in new window!",
                                        "Success"
                                    )
                                    refreshWorktrees()
                                } else {
                                    Messages.showErrorDialog(
                                        project,
                                        "Failed to create worktree. Check if the branch name already exists.",
                                        "Error"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }) {
            Text("Create Worktree")
        }

        // Worktree list
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading worktrees...")
            }
        } else if (worktrees.isEmpty()) {
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
                items(worktrees) { worktree ->
                    WorktreeItem(
                        worktree = worktree,
                        project = project,
                        onDelete = { worktreeToDelete ->
                            coroutineScope.launch(Dispatchers.IO) {
                                val repositoryManager = GitRepositoryManager.getInstance(project)
                                val repository = repositoryManager.repositories.firstOrNull()

                                if (repository != null) {
                                    val service = GitWorktreeService.getInstance(project)
                                    val success = service.deleteWorktree(repository, worktreeToDelete.path)

                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            Messages.showInfoMessage(
                                                project,
                                                "Worktree deleted successfully!",
                                                "Success"
                                            )
                                            refreshWorktrees()
                                        } else {
                                            Messages.showErrorDialog(
                                                project,
                                                "Failed to delete worktree.",
                                                "Error"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WorktreeItem(
    worktree: WorktreeInfo,
    project: Project,
    onDelete: (WorktreeInfo) -> Unit
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
            OutlinedButton(onClick = {
                val result = Messages.showYesNoDialog(
                    project,
                    "Are you sure you want to delete this worktree?\n${worktree.path}",
                    "Delete Worktree",
                    Messages.getWarningIcon()
                )
                if (result == Messages.YES) {
                    onDelete(worktree)
                }
            }) {
                Text("Delete")
            }
        }
    }
}