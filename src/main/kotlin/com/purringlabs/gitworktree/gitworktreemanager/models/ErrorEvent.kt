package com.purringlabs.gitworktree.gitworktreemanager.models

import kotlinx.serialization.Serializable

@Serializable
data class ErrorEvent(
    val errorId: String,
    val timestamp: Long,
    val error: StructuredError,
    val context: TelemetryContext
)
