package com.purringlabs.gitworktree.gitworktreemanager.models

import kotlinx.serialization.Serializable

@Serializable
data class TelemetryContext(
    val ideVersion: String,
    val pluginVersion: String,
    val osName: String,
    val osVersion: String,
    val jvmVersion: String,
    val countryCode: String,
    // Anonymous, persisted-per-install UUID. Enables DAU/WAU/MAU and retention via uniqueCount(install_id).
    val installId: String,
    // Random UUID generated once per IDE launch. Enables session-level metrics.
    val sessionId: String,
    // IDE product name, e.g. "IntelliJ IDEA" / "Android Studio" / "PyCharm". Segments the audience.
    val ideProduct: String
)
