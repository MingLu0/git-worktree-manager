package com.purringlabs.gitworktree.gitworktreemanager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.purringlabs.gitworktree.gitworktreemanager.models.NoRepositoryCtaEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.OpenWorktreeEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepository
import com.purringlabs.gitworktree.gitworktreemanager.services.FileOperationsService
import com.purringlabs.gitworktree.gitworktreemanager.services.GitWorktreeService
import com.purringlabs.gitworktree.gitworktreemanager.services.IgnoredFilesService
import com.purringlabs.gitworktree.gitworktreemanager.services.NoRepositoryUiHelper
import com.purringlabs.gitworktree.gitworktreemanager.services.TelemetryService
import com.purringlabs.gitworktree.gitworktreemanager.services.TelemetryServiceImpl
import com.purringlabs.gitworktree.gitworktreemanager.services.UiErrorMapper
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.CopyResultDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.ErrorDetailsDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.IgnoredFilesSelectionDialog
import git4idea.repo.GitRepositoryManager
import com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeViewModel
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

private fun showOperationError(project: Project, error: Throwable, operation: String) {
    val uiError = UiErrorMapper.map(error, operation)
    ErrorDetailsDialog(
        project = project,
        titleText = uiError.title,
        summary = uiError.summary,
        actions = uiError.actions,
        detailsText = uiError.details,
        copyText = uiError.copyText
    ).show()
}

private fun findValidRepository(project: Project): GitRepository? {
    return GitRepositoryManager.getInstance(project)
        .repositories
        .firstOrNull { repo -> File(repo.root.path).exists() }
}

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
    // UI scope: safe for UI interactions within the current composition.
    val uiScope = rememberCoroutineScope()

    // ViewModel scope: must outlive the composition to avoid ForgottenCoroutineScopeException when
    // long-running Git operations complete after the UI leaves composition.
    // Use a single-threaded dispatcher to serialize state updates (state = state.copy(...))
    // while still allowing repository methods to hop to Dispatchers.IO internally.
    val viewModelScope = remember(project) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    }

    val viewModel = remember(project) {
        WorktreeViewModel(
            project = project,
            coroutineScope = viewModelScope,
            repository = WorktreeRepository(project),
            ignoredFilesService = IgnoredFilesService.getInstance(project),
            fileOpsService = FileOperationsService.getInstance(project)
        )
    }

    // Initialize data on first composition
    LaunchedEffect(project) {
        viewModel.refreshWorktrees()
    }

    DisposableEffect(project) {
        val disposable = registerGitRepoAutoRefresh(
            project = project,
            requestAutoRefresh = viewModel::requestAutoRefresh,
            cancelAutoRefresh = viewModel::cancelAutoRefresh
        )
        onDispose {
            // Stop background jobs when the tool window content is disposed.
            viewModelScope.cancel()
            Disposer.dispose(disposable)
        }
    }

    val currentProjectBasePath = remember(project) { project.basePath }

    WorktreeListContent(
        state = viewModel.state,
        currentProjectBasePath = currentProjectBasePath,
        onSearchQueryChange = viewModel::setSearchQuery,
        onRefresh = {
            val repository = findValidRepository(project)
            if (repository == null) {
                NoRepositoryUiHelper.showNoRepositoryDialog(
                    project = project,
                    attemptedOperation = "LIST_WORKTREES",
                    telemetry = TelemetryServiceImpl.getInstance()
                )
                return@WorktreeListContent
            }
            viewModel.refreshWorktrees()
        },
        onOpenWorktree = { worktree ->
            openOrFocusWorktree(project, worktree.path, TelemetryServiceImpl.getInstance())
        },
        onCreateWorktree = { name, branch ->
            val repository = findValidRepository(project)
            if (repository == null) {
                NoRepositoryUiHelper.showNoRepositoryDialog(
                    project = project,
                    attemptedOperation = "CREATE_WORKTREE",
                    telemetry = TelemetryServiceImpl.getInstance()
                )
                return@WorktreeListContent
            }

            // Folder-exists and branch-exists prompting are handled upfront (right after entering name/branch).

            viewModel.createWorktree(
                name = name,
                branchName = branch,
                onSuccess = { createResult ->
                    ApplicationManager.getApplication().invokeLater {
                        // Open the worktree in a new window
                        ProjectUtil.openOrImport(File(createResult.path).toPath(), project, true)
                        Messages.showInfoMessage(
                            project,
                            if (createResult.created) {
                                "Worktree created and opened in new window!"
                            } else {
                                "Worktree already exists — opened existing worktree in new window."
                            },
                            "Success"
                        )
                    }
                },
                onError = { error ->
                    ApplicationManager.getApplication().invokeLater {
                        showOperationError(project, error, operation = "CREATE_WORKTREE")
                    }
                }
            )
        },
        onCreateWorktreeWithIgnoredFiles = { name, branch ->
            val repository = findValidRepository(project)
            if (repository == null) {
                NoRepositoryUiHelper.showNoRepositoryDialog(
                    project = project,
                    attemptedOperation = "CREATE_WORKTREE",
                    telemetry = TelemetryServiceImpl.getInstance()
                )
                return@WorktreeListContent
            }

            uiScope.launch {
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
                        onSuccess = { createResult ->
                            ApplicationManager.getApplication().invokeLater {
                                ProjectUtil.openOrImport(File(createResult.path).toPath(), project, true)
                                Messages.showInfoMessage(
                                    project,
                                    if (createResult.created) {
                                        "Worktree created and opened in new window!"
                                    } else {
                                        "Worktree already exists — opened existing worktree in new window."
                                    },
                                    "Success"
                                )
                            }
                        },
                        onError = { error ->
                            ApplicationManager.getApplication().invokeLater {
                                showOperationError(project, error, operation = "CREATE_WORKTREE")
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
                        onSuccess = { createResult ->
                            ApplicationManager.getApplication().invokeLater {
                                // Open the worktree in a new window
                                ProjectUtil.openOrImport(File(createResult.path).toPath(), project, true)

                                // Show copy results if available
                                val copyResult = viewModel.state.copyResult
                                if (copyResult != null && copyResult.successCount > 0) {
                                    val resultDialog = CopyResultDialog(project, copyResult)
                                    resultDialog.show()
                                }

                                Messages.showInfoMessage(
                                    project,
                                    if (createResult.created) {
                                        "Worktree created and opened in new window!"
                                    } else {
                                        "Worktree already exists — opened existing worktree in new window."
                                    },
                                    "Success"
                                )
                            }
                        },
                        onError = { error ->
                            ApplicationManager.getApplication().invokeLater {
                                showOperationError(project, error, operation = "CREATE_WORKTREE")
                            }
                        }
                    )
                } else {
                    // User cancelled selection - create worktree without copying files
                    viewModel.createWorktree(
                        name = name,
                        branchName = branch,
                        onSuccess = { createResult ->
                            ApplicationManager.getApplication().invokeLater {
                                ProjectUtil.openOrImport(File(createResult.path).toPath(), project, true)
                                Messages.showInfoMessage(
                                    project,
                                    if (createResult.created) {
                                        "Worktree created and opened in new window!"
                                    } else {
                                        "Worktree already exists — opened existing worktree in new window."
                                    },
                                    "Success"
                                )
                            }
                        },
                        onError = { error ->
                            ApplicationManager.getApplication().invokeLater {
                                showOperationError(project, error, operation = "CREATE_WORKTREE")
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
                onError = { error ->
                    ApplicationManager.getApplication().invokeLater {
                        showOperationError(project, error, operation = "DELETE_WORKTREE")
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
        onValidateWorktreeName = { initialName ->
            val repository = findValidRepository(project)
                ?: return@WorktreeListContent initialName

            val gitWorktreeService = GitWorktreeService.getInstance(project)
            var name = initialName

            while (true) {
                val path = gitWorktreeService.getWorktreePath(repository, name)
                val existingDir = File(path)
                if (!existingDir.exists()) return@WorktreeListContent name

                // IMPORTANT: do NOT run Git commands on the EDT.
                // Git authentication may need the IDE built-in server; waiting for it on the EDT triggers an assertion.
                val existingWorktree = try {
                    listWorktreesInBackground(project, repository)
                        ?.firstOrNull { it.path == path }
                } catch (_: ProcessCanceledException) {
                    // User cancelled validation; treat as cancel.
                    return@WorktreeListContent null
                }

                val choice = Messages.showDialog(
                    project,
                    "The worktree folder already exists:\n$path\n\nWhat would you like to do?",
                    "Worktree Folder Already Exists",
                    arrayOf("Open existing", "Use another name…", "Cancel"),
                    0,
                    Messages.getWarningIcon()
                )

                when (choice) {
                    0 -> {
                        if (existingWorktree != null) {
                            openOrFocusWorktree(project, existingWorktree.path, TelemetryServiceImpl.getInstance())
                        } else {
                            Messages.showErrorDialog(
                                project,
                                "Folder exists but is not a registered git worktree. Please choose another name or remove the folder manually.\n\n$path",
                                "Cannot Open Existing Folder"
                            )
                        }
                        return@WorktreeListContent null
                    }

                    1 -> {
                        val newName = Messages.showInputDialog(
                            project,
                            "Enter a different worktree name:",
                            "Choose Another Name",
                            Messages.getQuestionIcon(),
                            "${name}-2",
                            null
                        )

                        if (newName.isNullOrBlank()) return@WorktreeListContent null
                        name = newName
                    }

                    else -> return@WorktreeListContent null
                }
            }

            return@WorktreeListContent null
        },
        onValidateBranchName = { initialBranch ->
            val repository = findValidRepository(project)
                ?: return@WorktreeListContent initialBranch

            // IMPORTANT: do NOT run Git commands on the EDT.
            // Checking local branch existence should be done via repository state (not `git branch`),
            // otherwise Git may trigger auth helpers that assert when invoked from the UI thread.
            var branchName = initialBranch

            fun branchAlreadyExists(name: String): Boolean {
                return repository.branches.localBranches.any { it.name == name }
            }

            while (true) {
                if (!branchAlreadyExists(branchName)) {
                    return@WorktreeListContent branchName
                }

                val choice = Messages.showDialog(
                    project,
                    "Branch '$branchName' already exists. Please choose another name.",
                    "Branch Already Exists",
                    arrayOf("Use another name…", "Cancel"),
                    0,
                    Messages.getWarningIcon()
                )

                when (choice) {
                    0 -> {
                        val newBranch = Messages.showInputDialog(
                            project,
                            "Enter a new branch name:",
                            "Choose Another Branch Name",
                            Messages.getQuestionIcon(),
                            "${branchName}-2",
                            null
                        )

                        if (newBranch.isNullOrBlank()) return@WorktreeListContent null
                        branchName = newBranch
                    }

                    else -> return@WorktreeListContent null
                }
            }

            // Unreachable in practice, but keeps the lambda well-typed.
            return@WorktreeListContent null
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

@VisibleForTesting
internal fun canonicalizePath(path: String): String = FileUtil.toCanonicalPath(path)

@VisibleForTesting
internal fun isWorktreeAlreadyOpen(openProjectBasePaths: Sequence<String?>, worktreePath: String): Boolean {
    val canonicalTarget = canonicalizePath(worktreePath)
    return openProjectBasePaths
        .filterNotNull()
        .any { canonicalizePath(it) == canonicalTarget }
}

@VisibleForTesting
internal fun restoreFromMinimizedPreservingMaximized(extendedState: Int): Int {
    return extendedState and Frame.ICONIFIED.inv()
}

@VisibleForTesting
internal fun isCurrentWorktree(currentProjectBasePath: String?, worktreePath: String): Boolean {
    val canonicalCurrent = currentProjectBasePath?.let { canonicalizePath(it) } ?: return false
    return canonicalizePath(worktreePath) == canonicalCurrent
}

@VisibleForTesting
internal fun isDeleteEnabled(isMain: Boolean, isCurrent: Boolean, isDeleting: Boolean): Boolean {
    // Never allow deleting the main worktree, or the currently-open worktree.
    return !isDeleting && !isMain && !isCurrent
}

@VisibleForTesting
internal fun sortWorktreesForDisplay(
    worktrees: List<WorktreeInfo>,
    currentProjectBasePath: String?
): List<WorktreeInfo> {
    return worktrees.sortedWith(
        compareByDescending<WorktreeInfo> { wt ->
            isCurrentWorktree(currentProjectBasePath = currentProjectBasePath, worktreePath = wt.path)
        }
            // Pin main directly under current.
            // Note: in practice, current+main should never both be true at once, but this makes ordering stable.
            .thenByDescending { wt -> wt.isMain }
            .thenBy { wt -> wt.branch ?: "" }
            .thenBy { wt -> wt.path }
    )
}

private fun listWorktreesInBackground(project: Project, repository: GitRepository): List<WorktreeInfo>? {
    val gitWorktreeService = GitWorktreeService.getInstance(project)

    return try {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable {
                gitWorktreeService.listWorktrees(repository).getOrNull()
            },
            "Checking existing worktrees…",
            true,
            project
        )
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

private fun openOrFocusWorktree(
    currentProject: Project,
    worktreePath: String,
    telemetryService: TelemetryService
) {
    val operationId = UUID.randomUUID().toString()
    val startTime = System.currentTimeMillis()

    val alreadyOpenProject = ProjectManager.getInstance().openProjects.firstOrNull { p ->
        val base = p.basePath ?: return@firstOrNull false
        canonicalizePath(base) == canonicalizePath(worktreePath)
    }

    val alreadyOpen = isWorktreeAlreadyOpen(
        openProjectBasePaths = ProjectManager.getInstance().openProjects.asSequence().map { it.basePath },
        worktreePath = worktreePath
    )

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
                    frame.extendedState = restoreFromMinimizedPreservingMaximized(frame.extendedState)
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
    currentProjectBasePath: String?,
    onSearchQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenWorktree: (WorktreeInfo) -> Unit,
    onCreateWorktree: (name: String, branch: String) -> Unit,
    onCreateWorktreeWithIgnoredFiles: (name: String, branch: String) -> Unit,
    onDeleteWorktree: (WorktreeInfo) -> Unit,
    onRequestWorktreeName: () -> String?,
    onRequestBranchName: (defaultName: String) -> String?,
    onValidateWorktreeName: (name: String) -> String?,
    onValidateBranchName: (branchName: String) -> String?,
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
            val rawName = onRequestWorktreeName()
            if (!rawName.isNullOrBlank()) {
                val worktreeName = onValidateWorktreeName(rawName)
                if (!worktreeName.isNullOrBlank()) {
                    val rawBranchName = onRequestBranchName(worktreeName)
                if (!rawBranchName.isNullOrBlank()) {
                    val branchName = onValidateBranchName(rawBranchName)
                    if (!branchName.isNullOrBlank()) {
                        val copyIgnoredFiles = onRequestCopyIgnoredFiles()
                        if (copyIgnoredFiles) {
                            onCreateWorktreeWithIgnoredFiles(worktreeName, branchName)
                        } else {
                            onCreateWorktree(worktreeName, branchName)
                        }
                    }
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

        // Search
        val filteredWorktrees = remember(state.worktrees, state.searchQuery) {
            val q = state.searchQuery.trim().lowercase()
            if (q.isBlank()) return@remember state.worktrees

            state.worktrees.filter { wt ->
                val branch = wt.branch ?: ""
                listOf(branch, wt.path, wt.commit).any { it.lowercase().contains(q) }
            }
        }

        // Note: current-worktree detection is implemented via isCurrentWorktree(...)
        // and kept as a pure function to make it easy to unit-test.

        val searchBorder = if (isSystemInDarkTheme()) Color(0x33FFFFFF) else Color(0x22000000)
        val searchBg = if (isSystemInDarkTheme()) Color(0x14FFFFFF) else Color(0x0A000000)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = if (isSystemInDarkTheme()) Color.White else Color.Black),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, searchBorder, RoundedCornerShape(8.dp))
                    .background(searchBg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { innerTextField ->
                    Box {
                        if (state.searchQuery.isBlank()) {
                            Text(
                                text = "Search worktrees…",
                                fontWeight = FontWeight.Light
                            )
                        }
                        innerTextField()
                    }
                }
            )

            OutlinedButton(
                onClick = { onSearchQueryChange("") },
                enabled = state.searchQuery.isNotBlank()
            ) {
                Text("Clear")
            }

            OutlinedButton(onClick = onRefresh, enabled = !isBusy) {
                Text("Refresh")
            }
        }

        // Worktree list
        // Sort so the currently-open worktree is always at the top, and the main worktree is pinned just under it.
        val sortedWorktrees = remember(filteredWorktrees, currentProjectBasePath) {
            sortWorktreesForDisplay(
                worktrees = filteredWorktrees,
                currentProjectBasePath = currentProjectBasePath
            )
        }

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
        } else if (filteredWorktrees.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No matches")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedWorktrees) { worktree ->
                    val isCurrent = isCurrentWorktree(
                        currentProjectBasePath = currentProjectBasePath,
                        worktreePath = worktree.path
                    )

                    WorktreeItem(
                        worktree = worktree,
                        isCurrent = isCurrent,
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
    isCurrent: Boolean,
    isDeleting: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }

    val currentBackground = when {
        !isCurrent -> Color.Transparent
        isSystemInDarkTheme() -> Color(0x162F80FF) // subtle blue tint
        else -> Color(0x142F80FF)
    }

    val hoverBackground = when {
        !isHovered -> Color.Transparent
        isSystemInDarkTheme() -> Color(0x22FFFFFF)
        else -> Color(0x14000000)
    }

    // If it's both current + hovered, blend by just preferring hover.
    val rowBackground = if (isHovered) hoverBackground else currentBackground

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
            }
            .background(rowBackground)
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

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isCurrent) {
                        Text(text = "(current)", fontWeight = FontWeight.Light)
                    }
                    if (worktree.isMain) {
                        Text(text = "(main)", fontWeight = FontWeight.Light)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = worktree.path,
                fontWeight = FontWeight.Light
            )
            if (worktree.isMain) {
                Text(
                    text = "Main working tree (repo root)",
                    fontWeight = FontWeight.Light
                )
            }
            Text(
                text = "Commit: ${worktree.commit.take(8)}",
                fontWeight = FontWeight.Light
            )

            // Avoid layout jitter in the list: always reserve space for the hint line,
            // and fade it in/out via alpha.
            val hintAlpha = if (isHovered) 1f else 0f
            Text(
                text = "Tip: double-click row to open", 
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .graphicsLayer(alpha = hintAlpha)
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Delete button
            // - Non-main: enabled (unless deleting)
            // - Main: shown but disabled, with tooltip explaining why
            val deleteEnabled = isDeleteEnabled(isMain = worktree.isMain, isCurrent = isCurrent, isDeleting = isDeleting)
            var isDeleteHovered by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    // Track hover on the container so we still get hover events when the button is disabled.
                    .pointerMoveFilter(
                        onEnter = {
                            isDeleteHovered = true
                            false
                        },
                        onExit = {
                            isDeleteHovered = false
                            false
                        }
                    )
            ) {
                OutlinedButton(onClick = { if (deleteEnabled) onDelete() }, enabled = deleteEnabled) {
                    Text("Delete")
                }

                if (!deleteEnabled && isDeleteHovered) {
                    val tooltipText = when {
                        worktree.isMain -> "Main working tree can’t be removed."
                        isCurrent -> "This worktree is currently open. Close it (or delete from another window) to remove it."
                        else -> null
                    }

                    if (tooltipText != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(y = (-40).dp)
                                .background(
                                    if (isSystemInDarkTheme()) Color(0xEE2B2B2B) else Color(0xEEFFFFFF),
                                    RoundedCornerShape(6.dp)
                                )
                                .border(1.dp, if (isSystemInDarkTheme()) Color(0x55FFFFFF) else Color(0x22000000), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = tooltipText,
                                fontWeight = FontWeight.Light
                            )
                        }
                    }
                }
            }
        }
    }
}
