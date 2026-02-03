package com.purringlabs.gitworktree.gitworktreemanager.models

import kotlinx.serialization.Serializable

@Serializable
data class StructuredError(
    val errorType: String,
    val errorMessage: String,
    val gitCommand: String?,
    val gitExitCode: Int?,
    val gitErrorOutput: String?,
    val stackTrace: String?
)

enum class ErrorType {
    GIT_COMMAND_FAILED,
    NO_REPOSITORY,
    WORKTREE_ALREADY_EXISTS,
    BRANCH_DELETE_FAILED,
    FILE_OPERATION_FAILED,
    UNKNOWN_ERROR
}
