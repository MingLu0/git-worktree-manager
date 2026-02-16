package com.purringlabs.gitworktree.gitworktreemanager.services

import com.purringlabs.gitworktree.gitworktreemanager.exceptions.NoRepositoryException
import com.purringlabs.gitworktree.gitworktreemanager.exceptions.WorktreeOperationException

/**
 * Maps throwables to a user-friendly message + actionable steps, while preserving exact technical details.
 */
object UiErrorMapper {

    data class UiError(
        val title: String,
        val summary: String,
        val actions: List<String> = emptyList(),
        val details: String? = null,
        val copyText: String? = null
    )

    fun map(throwable: Throwable, operation: String? = null): UiError {
        val root = rootCause(throwable)
        val msg = (root.message ?: throwable.message ?: "Unknown error").trim()

        // Special-case: process start failure due to missing working directory
        if (isMissingWorkingDirectory(msg)) {
            val wd = extractMissingWorkingDirectory(msg)
            val details = buildDetails(
                operation = operation,
                command = null,
                workingDirectory = wd,
                exitCode = null,
                errorOutput = msg
            )

            return UiError(
                title = "Git Worktree Manager — Project folder no longer exists",
                summary = "Git couldn’t run because the repository folder is missing or was moved.",
                actions = listOf(
                    "Re-open the project from the correct folder (File → Open…)",
                    "Remove the stale entry from Recent Projects, then open the real one again",
                    "If the folder was deleted by mistake, restore it and reopen the project"
                ),
                details = details,
                copyText = buildCopyText(details)
            )
        }

        return when (throwable) {
            is NoRepositoryException -> UiError(
                title = "Git Worktree Manager — No Git repository found",
                summary = "I couldn’t find a Git repository in this project, so I can’t manage worktrees.",
                actions = listOf(
                    "Open the project from a folder that contains a .git directory",
                    "If this is a multi-module project, make sure the Git root is included in the IDE",
                    "If you cloned the repo recently, try reopening the project"
                ),
                details = buildDetails(operation = operation, errorOutput = msg),
                copyText = buildCopyText(buildDetails(operation = operation, errorOutput = msg))
            )

            is WorktreeOperationException -> {
                val details = buildDetails(
                    operation = operation,
                    command = throwable.gitCommand,
                    workingDirectory = null,
                    exitCode = throwable.gitExitCode,
                    errorOutput = throwable.gitErrorOutput ?: msg
                )

                UiError(
                    title = "Git Worktree Manager — Git command failed",
                    summary = "Git ran but returned an error while trying to complete the operation.",
                    actions = listOf(
                        "Open the Terminal in that repo and run the command in Details",
                        "Make sure you have permission to access the folder and the repo isn’t locked",
                        "If this keeps happening, share the copied details in an issue"
                    ),
                    details = details,
                    copyText = buildCopyText(details)
                )
            }

            else -> {
                val details = buildDetails(operation = operation, errorOutput = msg)
                UiError(
                    title = "Git Worktree Manager — Unexpected error",
                    summary = "Something went wrong while trying to complete the operation.",
                    actions = listOf(
                        "Try again",
                        "If it keeps happening, copy the details and share them in an issue"
                    ),
                    details = details,
                    copyText = buildCopyText(details)
                )
            }
        }
    }

    private fun rootCause(t: Throwable): Throwable {
        var cur: Throwable = t
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return cur
    }

    private fun isMissingWorkingDirectory(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("working directory") && (m.contains("does not exist") || m.contains("no such file"))
    }

    private fun extractMissingWorkingDirectory(message: String): String? {
        // Try to extract: working directory '/path' does not exist
        val regex = Regex("working directory ['\"]([^'\"]+)['\"]")
        return regex.find(message)?.groupValues?.getOrNull(1)?.let { sanitizePaths(it) }
    }

    private fun buildDetails(
        operation: String? = null,
        command: String? = null,
        workingDirectory: String? = null,
        exitCode: Int? = null,
        errorOutput: String? = null
    ): String {
        return buildString {
            appendLine("Details (for debugging):")
            if (!operation.isNullOrBlank()) appendLine("Operation: ${operation}")
            if (!command.isNullOrBlank()) appendLine("Command: ${sanitizePaths(command)}")
            if (!workingDirectory.isNullOrBlank()) appendLine("Working directory: ${sanitizePaths(workingDirectory)}")
            if (exitCode != null) appendLine("Exit code: ${exitCode}")
            if (!errorOutput.isNullOrBlank()) {
                appendLine("Error:")
                appendLine(sanitizePaths(errorOutput.trim()))
            }
        }.trim()
    }

    private fun buildCopyText(details: String): String {
        // Provide a stable, pasteable block.
        return details.trim()
    }

    private fun sanitizePaths(input: String): String {
        // macOS user home path redaction
        return input.replace(Regex("/Users/[^/]+/"), "/Users/<user>/")
    }
}
