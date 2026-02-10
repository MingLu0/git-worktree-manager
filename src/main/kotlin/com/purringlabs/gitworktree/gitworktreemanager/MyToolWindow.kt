package com.purringlabs.gitworktree.gitworktreemanager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.WindowManager
import com.purringlabs.gitworktree.gitworktreemanager.models.OpenWorktreeEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepository
import com.purringlabs.gitworktree.gitworktreemanager.services.FileOperationsService
import com.purringlabs.gitworktree.gitworktreemanager.services.IgnoredFilesService
import com.purringlabs.gitworktree.gitworktreemanager.services.TelemetryService
import com.purringlabs.gitworktree.gitworktreemanager.services.TelemetryServiceImpl
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.CopyResultDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.IgnoredFilesSelectionDialog
import com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeViewModel
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.awt.Cursor
import java.awt.Frame
import java.io.File
import java.util.UUID
import javax.swing.JProgressBar

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
    val viewModel = remember {
        WorktreeViewModel(
            project = project,
            coroutineScope = coroutineScope,
            repository = WorktreeRepository(project),
            ignoredFilesService = IgnoredFilesService.getInstance(project),
            fileOpsService = FileOperationsService.getInstance(project)
        )
    }

    // Initialize data on first composition
    LaunchedEffect(Unit) {
        viewModel.refreshWorktrees()
    }

    DisposableEffect(project) {
        val disposable = registerGitRepoAutoRefresh(
            project = project,
            requestAutoRefresh = viewModel::requestAutoRefresh,
            cancelAutoRefresh = viewModel::cancelAutoRefresh
        )
        onDispose { Disposer.dispose(disposable) }
    }

    WorktreeListContent(
        state = viewModel.state,
        onRefresh = {
            viewModel.refreshWorktrees()
        },
        onOpenWorktree = { worktree ->
            openOrFocusWorktree(project, worktree.path, TelemetryServiceImpl.getInstance())
        },
        onCreateWorktree = { name, branch ->
            viewModel.createWorktree(
                name = name,
                branchName = branch,
                onSuccess = { worktreePath ->
                    ApplicationManager.getApplication().invokeLater {
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
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            errorMessage,
                            "Error"
                        )
                    }
                }
            )
        },
        onCreateWorktreeWithIgnoredFiles = { name, branch ->
            coroutineScope.launch {
                // Step 1: Scan for ignored files
                viewModel.scanIgnoredFiles()

                // Step 2: Check for scan errors
                if (viewModel.state.scanError != null) {
                    withContext(Dispatchers.Main) {
                        Messages.showErrorDialog(
                            project,
                            "Failed to scan ignored files: ${viewModel.state.scanError}",
                            "Error"
                        )
                    }
                    return@launch
                }

                // Step 3: Show selection dialog
                val ignoredFiles = viewModel.state.ignoredFiles
                if (ignoredFiles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Messages.showInfoMessage(
                            project,
                            "No ignored files found.",
                            "No Ignored Files"
                        )
                    }
                    // Still create the worktree without copying files
                    viewModel.createWorktree(
                        name = name,
                        branchName = branch,
                        onSuccess = { worktreePath ->
                            ApplicationManager.getApplication().invokeLater {
                                ProjectUtil.openOrImport(File(worktreePath).toPath(), project, true)
                                Messages.showInfoMessage(
                                    project,
                                    "Worktree created and opened in new window!",
                                    "Success"
                                )
                            }
                        },
                        onError = { errorMessage ->
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    project,
                                    errorMessage,
                                    "Error"
                                )
                            }
                        }
                    )
                    return@launch
                }

                // Show dialog on main thread
                val selectedFiles = withContext(Dispatchers.Main) {
                    val dialog = IgnoredFilesSelectionDialog(project, ignoredFiles)
                    if (dialog.showAndGet()) {
                        dialog.getSelectedFiles()
                    } else {
                        null // User cancelled
                    }
                }

                // Step 4: Create worktree with or without selected files
                if (selectedFiles != null) {
                    // User selected files - create worktree with copying
                    viewModel.createWorktreeWithIgnoredFiles(
                        worktreeName = name,
                        branchName = branch,
                        selectedFiles = selectedFiles,
                        onSuccess = { worktreePath ->
                            ApplicationManager.getApplication().invokeLater {
                                // Open the worktree in a new window
                                ProjectUtil.openOrImport(File(worktreePath).toPath(), project, true)

                                // Show copy results if available
                                val copyResult = viewModel.state.copyResult
                                if (copyResult != null && copyResult.successCount > 0) {
                                    val resultDialog = CopyResultDialog(project, copyResult)
                                    resultDialog.show()
                                }

                                Messages.showInfoMessage(
                                    project,
                                    "Worktree created and opened in new window!",
                                    "Success"
                                )
                            }
                        },
                        onError = { errorMessage ->
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    project,
                                    errorMessage,
                                    "Error"
                                )
                            }
                        }
                    )
                } else {
                    // User cancelled selection - create worktree without copying files
                    viewModel.createWorktree(
                        name = name,
                        branchName = branch,
                        onSuccess = { worktreePath ->
                            ApplicationManager.getApplication().invokeLater {
                                ProjectUtil.openOrImport(File(worktreePath).toPath(), project, true)
                                Messages.showInfoMessage(
                                    project,
                                    "Worktree created and opened in new window!",
                                    "Success"
                                )
                            }
                        },
                        onError = { errorMessage ->
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    project,
                                    errorMessage,
                                    "Error"
                                )
                            }
                        }
                    )
                }
            }
        },
        onDeleteWorktree = { worktree ->
            viewModel.deleteWorktree(
                worktreePath = worktree.path,
                onSuccess = {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(
                            project,
                            "Worktree deleted successfully!",
                            "Success"
                        )
                    }
                },
                onError = { errorMessage ->
                    ApplicationManager.getApplication().invokeLater {
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
        },
        onRequestCopyIgnoredFiles = {
            val result = Messages.showYesNoDialog(
                project,
                "Do you want to copy ignored files to the new worktree?",
                "Copy Ignored Files",
                Messages.getQuestionIcon()
            )
            result == Messages.YES
        }
    )
}

@VisibleForTesting
internal fun registerGitRepoAutoRefresh(
    project: Project,
    requestAutoRefresh: () -> Unit,
    cancelAutoRefresh: () -> Unit
): Disposable {
    val connection = project.messageBus.connect()
    connection.subscribe(
        GitRepository.GIT_REPO_CHANGE,
        GitRepositoryChangeListener { requestAutoRefresh() }
    )
    return Disposable {
        cancelAutoRefresh()
        connection.dispose()
    }
}

private fun openOrFocusWorktree(
    currentProject: Project,
    worktreePath: String,
    telemetryService: TelemetryService
) {
    val operationId = UUID.randomUUID().toString()
    val startTime = System.currentTimeMillis()

    val canonicalTarget = FileUtil.toCanonicalPath(worktreePath)

    val alreadyOpenProject = ProjectManager.getInstance().openProjects.firstOrNull { p ->
        val base = p.basePath ?: return@firstOrNull false
        FileUtil.toCanonicalPath(base) == canonicalTarget
    }

    val alreadyOpen = alreadyOpenProject != null

    // Note: invokeLater schedules execution on the EDT. Record telemetry *inside* the EDT action
    // so success/duration reflect the actual work, not just scheduling.
    ApplicationManager.getApplication().invokeLater {
        val execStart = System.currentTimeMillis()
        val result = runCatching {
            if (alreadyOpenProject != null) {
                // Prefer IDE focus APIs; fall back to raw frame-toFront.
                val ideFrame = WindowManager.getInstance().getIdeFrame(alreadyOpenProject)
                if (ideFrame != null) {
                    IdeFocusManager.getInstance(alreadyOpenProject).requestFocus(ideFrame.component, true)
                }

                val frame = WindowManager.getInstance().getFrame(alreadyOpenProject)
                if (frame != null) {
                    // Only restore from minimized; do not clear maximized state.
                    frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
                    frame.toFront()
                    frame.requestFocus()
                }
            } else {
                ProjectUtil.openOrImport(File(worktreePath).toPath(), currentProject, true)
            }
        }

        telemetryService.recordOperation(
            OpenWorktreeEvent(
                operationId = operationId,
                startTime = startTime,
                durationMs = System.currentTimeMillis() - execStart,
                success = result.isSuccess,
                context = telemetryService.getContext(),
                worktreePath = worktreePath,
                alreadyOpen = alreadyOpen
            )
        )
    }
}
/**
 * Pure UI composable for displaying the worktree list
 * No dependency on Project - can be previewed with mock data
 */
@Composable
private fun WorktreeListContent(
    state: com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeState,
    onRefresh: () -> Unit,
    onOpenWorktree: (WorktreeInfo) -> Unit,
    onCreateWorktree: (name: String, branch: String) -> Unit,
    onCreateWorktreeWithIgnoredFiles: (name: String, branch: String) -> Unit,
    onDeleteWorktree: (WorktreeInfo) -> Unit,
    onRequestWorktreeName: () -> String?,
    onRequestBranchName: (defaultName: String) -> String?,
    onConfirmDelete: (WorktreeInfo) -> Boolean,
    onRequestCopyIgnoredFiles: () -> Boolean
) {
    val isBusy = state.isCreating || state.isScanning || state.deletingWorktreePath != null
    val statusText = when {
        state.isScanning -> "Scanning ignored files..."
        state.isCreating -> "Creating worktree..."
        state.deletingWorktreePath != null -> "Deleting worktree..."
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isBusy) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SwingPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    factory = {
                        JProgressBar().apply {
                            isIndeterminate = true
                            border = null
                        }
                    }
                )
                statusText?.let { Text(it) }
            }
        }

        // Create button at the top
        OutlinedButton(onClick = {
            val worktreeName = onRequestWorktreeName()
            if (!worktreeName.isNullOrBlank()) {
                val branchName = onRequestBranchName(worktreeName)
                if (!branchName.isNullOrBlank()) {
                    val copyIgnoredFiles = onRequestCopyIgnoredFiles()
                    if (copyIgnoredFiles) {
                        onCreateWorktreeWithIgnoredFiles(worktreeName, branchName)
                    } else {
                        onCreateWorktree(worktreeName, branchName)
                    }
                }
            }
        }, enabled = !state.isCreating && !state.isScanning) {
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
                        isDeleting = state.deletingWorktreePath == worktree.path,
                        onOpen = { onOpenWorktree(worktree) },
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun WorktreeItem(
    worktree: WorktreeInfo,
    isDeleting: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }
    val hoverBackground = when {
        !isHovered -> Color.Transparent
        isSystemInDarkTheme() -> Color(0x22FFFFFF)
        else -> Color(0x14000000)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerMoveFilter(
                onEnter = {
                    isHovered = true
                    false
                },
                onExit = {
                    isHovered = false
                    false
                }
            )
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
            // Double-click to open (avoid accidental opens while selecting/copying)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onOpen() }
                )
            )
            .background(hoverBackground)
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

            // Avoid layout jitter in the list: always reserve space for the hint line,
            // and fade it in/out via alpha.
            val hintAlpha = if (isHovered) 1f else 0f
            Text(
                text = "Double-click to open",
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .graphicsLayer(alpha = hintAlpha)
            )
        }

        // Delete button (only show for non-main worktrees)
        if (!worktree.isMain) {
            OutlinedButton(onClick = onDelete, enabled = !isDeleting) {
                Text("Delete")
            }
        }
    }
}
