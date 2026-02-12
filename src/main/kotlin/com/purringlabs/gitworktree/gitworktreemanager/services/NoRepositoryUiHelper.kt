package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.purringlabs.gitworktree.gitworktreemanager.models.NoRepositoryCtaEvent
import java.util.UUID

object NoRepositoryUiHelper {

    private const val CTA_OPEN_GIT = "open_git_tool_window"
    private const val CTA_REFRESH = "refresh_repositories"
    private const val CTA_HOWTO = "how_to_fix"

    fun showNoRepositoryDialog(
        project: Project,
        attemptedOperation: String,
        telemetry: TelemetryService
    ) {
        val choice = Messages.showDialog(
            project,
            "No Git repository detected in this project.\n\n" +
                "Fixes:\n" +
                "• Open the repository root folder (the one containing .git)\n" +
                "• Or enable Git: VCS → Enable Version Control Integration…\n",
            "No Git Repository",
            arrayOf("Open Git", "Refresh", "How to fix"),
            0,
            Messages.getWarningIcon()
        )

        val cta = when (choice) {
            0 -> CTA_OPEN_GIT
            1 -> CTA_REFRESH
            2 -> CTA_HOWTO
            else -> return
        }

        telemetry.recordOperation(
            NoRepositoryCtaEvent(
                operationId = UUID.randomUUID().toString(),
                startTime = System.currentTimeMillis(),
                durationMs = 0,
                success = true,
                context = telemetry.getContext(),
                attemptedOperation = attemptedOperation,
                cta = cta
            )
        )

        when (choice) {
            0 -> {
                val twm = ToolWindowManager.getInstance(project)
                val toolWindow = twm.getToolWindow("Git") ?: twm.getToolWindow("Version Control")
                toolWindow?.activate(null)
            }

            1 -> {
                // Best-effort: repository refresh. The UI will re-attempt once the user clicks again.
                // `update()` is available on the GitRepository; the manager will pick up changes.
                git4idea.repo.GitRepositoryManager.getInstance(project).repositories.forEach { it.update() }
            }

            2 -> {
                Messages.showInfoMessage(
                    project,
                    "How to fix:\n\n" +
                        "1) Open the repo root folder (it must contain a .git directory).\n" +
                        "2) If it’s not a git repo, initialize or enable Git via:\n" +
                        "   VCS → Enable Version Control Integration… → Git\n" +
                        "3) If you just opened the project, wait for indexing to finish and try again.\n",
                    "How to Fix: No Git Repository"
                )
            }
        }
    }
}
