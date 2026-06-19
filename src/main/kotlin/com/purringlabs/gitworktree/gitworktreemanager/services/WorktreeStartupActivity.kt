package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Emits a single passive session heartbeat once the IDE finishes starting up with a project open.
 *
 * This runs regardless of whether the user opens the worktree tool window, so daily-active-user
 * counts capture present-but-idle users instead of only users who explicitly act on a worktree.
 * The heartbeat itself is de-duplicated per IDE launch by [TelemetryService.recordSessionHeartbeat],
 * so opening multiple projects does not over-count.
 */
class WorktreeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        TelemetryServiceImpl.getInstance().recordSessionHeartbeat()
    }
}
