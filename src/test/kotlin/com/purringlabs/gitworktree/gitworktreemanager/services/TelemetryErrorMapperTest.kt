package com.purringlabs.gitworktree.gitworktreemanager.services

import com.purringlabs.gitworktree.gitworktreemanager.exceptions.NoRepositoryException
import com.purringlabs.gitworktree.gitworktreemanager.exceptions.WorktreeOperationException
import com.purringlabs.gitworktree.gitworktreemanager.models.ErrorType
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
