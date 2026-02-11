package com.purringlabs.gitworktree.gitworktreemanager.models

import kotlinx.serialization.Serializable

@Serializable
sealed interface OperationEvent {
    val operationId: String
    val operationType: String
    val startTime: Long
    val durationMs: Long
    val success: Boolean
    val context: TelemetryContext
}

@Serializable
data class CreateWorktreeEvent(
    override val operationId: String,
    override val startTime: Long,
    override val durationMs: Long,
    override val success: Boolean,
    override val context: TelemetryContext,
    val worktreeName: String,
    val branchName: String,
    val error: StructuredError?
) : OperationEvent {
    override val operationType: String = "CREATE_WORKTREE"
}

@Serializable
data class DeleteWorktreeEvent(
    override val operationId: String,
    override val startTime: Long,
    override val durationMs: Long,
    override val success: Boolean,
    override val context: TelemetryContext,
    val worktreeName: String,
    val branchDeleted: Boolean,
    val error: StructuredError?
) : OperationEvent {
    override val operationType: String = "DELETE_WORKTREE"
}

@Serializable
data class ListWorktreesEvent(
    override val operationId: String,
    override val startTime: Long,
    override val durationMs: Long,
    override val success: Boolean,
    override val context: TelemetryContext,
    val worktreeCount: Int,
    val error: StructuredError?
) : OperationEvent {
    override val operationType: String = "LIST_WORKTREES"
}

@Serializable
data class CopyFilesEvent(
    override val operationId: String,
    override val startTime: Long,
    override val durationMs: Long,
    override val success: Boolean,
    override val context: TelemetryContext,
    val itemCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val error: StructuredError?
) : OperationEvent {
    override val operationType: String = "COPY_FILES"
}

@Serializable
data class OpenWorktreeEvent(
    override val operationId: String,
    override val startTime: Long,
    override val durationMs: Long,
    override val success: Boolean,
    override val context: TelemetryContext,
    val worktreePath: String,
    val alreadyOpen: Boolean
) : OperationEvent {
    override val operationType: String = "OPEN_WORKTREE"
}
