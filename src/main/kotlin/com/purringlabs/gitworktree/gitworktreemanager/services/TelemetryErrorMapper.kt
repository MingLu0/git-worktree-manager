package com.purringlabs.gitworktree.gitworktreemanager.services

import com.purringlabs.gitworktree.gitworktreemanager.exceptions.NoRepositoryException
import com.purringlabs.gitworktree.gitworktreemanager.exceptions.WorktreeOperationException
import com.purringlabs.gitworktree.gitworktreemanager.models.ErrorType
import com.purringlabs.gitworktree.gitworktreemanager.models.StructuredError

object TelemetryErrorMapper {

    private const val MAX_GIT_ERROR_OUTPUT_LENGTH = 500
    private val unixHomeDirectoryPattern = Regex("(/Users|/home)/[^/\\\\\\s'\"]+")
    private val windowsHomeDirectoryPattern = Regex("([A-Za-z]:\\\\Users)\\\\[^\\\\/\\s'\"]+")

    fun mapToStructuredError(throwable: Throwable?): StructuredError? {
        if (throwable == null) return null

        val unsanitizedError = when (throwable) {
            is WorktreeOperationException -> StructuredError(
                errorType = inferErrorType(throwable),
                errorMessage = throwable.message ?: "Unknown error",
                gitCommand = throwable.gitCommand,
                gitExitCode = throwable.gitExitCode,
                gitErrorOutput = throwable.gitErrorOutput,
                stackTrace = null
            )
            is NoRepositoryException -> StructuredError(
                errorType = ErrorType.NO_REPOSITORY.name,
                errorMessage = throwable.message ?: "No repository found",
                gitCommand = null,
                gitExitCode = null,
                gitErrorOutput = null,
                stackTrace = null
            )
            else -> StructuredError(
                errorType = ErrorType.UNKNOWN_ERROR.name,
                errorMessage = throwable.message ?: throwable::class.simpleName ?: "Unknown error",
                gitCommand = null,
                gitExitCode = null,
                gitErrorOutput = null,
                stackTrace = throwable.stackTraceToString().lines().take(5).joinToString("\n")
            )
        }
        return sanitize(unsanitizedError)
    }

    fun sanitize(error: StructuredError): StructuredError {
        return error.copy(
            errorMessage = maskHomeDirectories(error.errorMessage),
            gitCommand = error.gitCommand?.let(::maskHomeDirectories),
            gitErrorOutput = error.gitErrorOutput?.let { maskHomeDirectories(it).take(MAX_GIT_ERROR_OUTPUT_LENGTH) },
            stackTrace = error.stackTrace?.let(::maskHomeDirectories)
        )
    }

    private fun inferErrorType(exception: WorktreeOperationException): String {
        val errorOutput = exception.gitErrorOutput?.lowercase() ?: ""

        return when {
            errorOutput.contains("already exists") -> ErrorType.WORKTREE_ALREADY_EXISTS.name
            errorOutput.contains("not a git repository") -> ErrorType.NO_REPOSITORY.name
            exception.gitCommand?.contains("branch") == true -> ErrorType.BRANCH_DELETE_FAILED.name
            else -> ErrorType.GIT_COMMAND_FAILED.name
        }
    }

    private fun maskHomeDirectories(text: String): String {
        return text
            .replace(unixHomeDirectoryPattern, "$1/<username>")
            .replace(windowsHomeDirectoryPattern, "$1\\\\<username>")
    }
}
