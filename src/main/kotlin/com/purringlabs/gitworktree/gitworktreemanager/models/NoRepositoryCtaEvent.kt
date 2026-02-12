package com.purringlabs.gitworktree.gitworktreemanager.models

import kotlinx.serialization.Serializable

/**
 * Telemetry event emitted when the user hits a "no repository" error and chooses a call-to-action.
 *
 * This helps us measure how often users run the plugin outside of a Git repo and which guidance paths
 * they take.
 */
@Serializable
data class NoRepositoryCtaEvent(
    override val operationId: String,
    override val startTime: Long,
    override val durationMs: Long,
    override val success: Boolean,
    override val context: TelemetryContext,
    val attemptedOperation: String,
    val cta: String
) : OperationEvent {
    override val operationType: String = "NO_REPOSITORY_CTA"
}
