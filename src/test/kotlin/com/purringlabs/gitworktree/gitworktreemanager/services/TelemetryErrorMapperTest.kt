package com.purringlabs.gitworktree.gitworktreemanager.services

import com.purringlabs.gitworktree.gitworktreemanager.exceptions.NoRepositoryException
import com.purringlabs.gitworktree.gitworktreemanager.exceptions.WorktreeOperationException
import com.purringlabs.gitworktree.gitworktreemanager.models.ErrorType
import com.purringlabs.gitworktree.gitworktreemanager.models.StructuredError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryErrorMapperTest {

    @Test
    fun `maps WorktreeOperationException with git details`() {
        val exception = WorktreeOperationException(
            message = "Failed",
            gitCommand = "git worktree add",
            gitExitCode = 1,
            gitErrorOutput = "fatal: already exists"
        )

        val error = TelemetryErrorMapper.mapToStructuredError(exception)

        assertNotNull(error)
        assertEquals(ErrorType.WORKTREE_ALREADY_EXISTS.name, error.errorType)
        assertEquals("Failed", error.errorMessage)
        assertEquals("git worktree add", error.gitCommand)
        assertEquals(1, error.gitExitCode)
        assertTrue(error.gitErrorOutput?.contains("already exists") == true)
        assertNull(error.stackTrace)
    }

    @Test
    fun `maps NoRepositoryException to no repository error`() {
        val exception = NoRepositoryException("No Git repository found")

        val error = TelemetryErrorMapper.mapToStructuredError(exception)

        assertNotNull(error)
        assertEquals(ErrorType.NO_REPOSITORY.name, error.errorType)
        assertEquals("No Git repository found", error.errorMessage)
        assertNull(error.gitCommand)
    }

    @Test
    fun `maps unknown exception with stack trace`() {
        val exception = IllegalStateException("boom")

        val error = TelemetryErrorMapper.mapToStructuredError(exception)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_ERROR.name, error.errorType)
        assertEquals("boom", error.errorMessage)
        assertTrue(!error.stackTrace.isNullOrBlank())
    }

    @Test
    fun `sanitizes git output with user paths`() {
        val exception = WorktreeOperationException(
            message = "Failed",
            gitErrorOutput = "fatal: /Users/ming/dev/repo already exists"
        )

        val error = TelemetryErrorMapper.mapToStructuredError(exception)

        assertNotNull(error)
        assertTrue(error.gitErrorOutput?.contains("/Users/<username>/") == true)
    }

    @Test
    fun `masks home directories in message and git command`() {
        val exception = WorktreeOperationException(
            message = "Failed to remove worktree '/Users/alice/projects/app-feature'",
            gitCommand = "git worktree remove C:/Users/bob/wt/feature --force",
            gitErrorOutput = "failed to delete '/home/carol/wt/feature': Directory not empty"
        )

        val error = TelemetryErrorMapper.mapToStructuredError(exception)

        assertNotNull(error)
        assertEquals("Failed to remove worktree '/Users/<username>/projects/app-feature'", error.errorMessage)
        assertEquals("git worktree remove C:/Users/<username>/wt/feature --force", error.gitCommand)
        assertEquals("failed to delete '/home/<username>/wt/feature': Directory not empty", error.gitErrorOutput)
    }

    @Test
    fun `masks windows backslash home directories`() {
        val error = TelemetryErrorMapper.sanitize(
            StructuredError(
                errorType = ErrorType.GIT_COMMAND_FAILED.name,
                errorMessage = "Failed to remove worktree 'D:\\Users\\dave\\wt\\feature'",
                gitCommand = null,
                gitExitCode = null,
                gitErrorOutput = null,
                stackTrace = null
            )
        )

        assertEquals("Failed to remove worktree 'D:\\Users\\<username>\\wt\\feature'", error.errorMessage)
    }

    @Test
    fun `sanitize masks branch delete errors built outside the mapper`() {
        val branchDeleteError = StructuredError(
            errorType = ErrorType.BRANCH_DELETE_FAILED.name,
            errorMessage = "Failed to delete branch 'feature' after removing worktree",
            gitCommand = "git branch -D feature",
            gitExitCode = 0,
            gitErrorOutput = "Cannot start a process, the working directory '/Users/erin/wt/feature' does not exist",
            stackTrace = null
        )

        val sanitizedError = TelemetryErrorMapper.sanitize(branchDeleteError)

        assertEquals(
            "Cannot start a process, the working directory '/Users/<username>/wt/feature' does not exist",
            sanitizedError.gitErrorOutput
        )
        assertFalse(sanitizedError.toString().contains("erin"))
    }
}
