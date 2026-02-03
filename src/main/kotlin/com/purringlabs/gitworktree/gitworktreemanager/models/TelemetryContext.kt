package com.purringlabs.gitworktree.gitworktreemanager.models

import kotlinx.serialization.Serializable

@Serializable
data class TelemetryContext(
    val ideVersion: String,
    val pluginVersion: String,
    val osName: String,
    val osVersion: String,
    val jvmVersion: String
)
