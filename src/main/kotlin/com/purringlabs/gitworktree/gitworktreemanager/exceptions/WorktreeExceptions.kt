package com.purringlabs.gitworktree.gitworktreemanager.exceptions

sealed class WorktreeException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class WorktreeOperationException(
    message: String,
    val gitCommand: String? = null,
    val gitExitCode: Int? = null,
    val gitErrorOutput: String? = null,
    cause: Throwable? = null
) : WorktreeException(message, cause)

class NoRepositoryException(message: String) : WorktreeException(message)
