package com.purringlabs.gitworktree.gitworktreemanager.models

import java.nio.file.Path
import java.nio.file.attribute.FileTime

data class AgentContextCopyOption(
    val id: String,
    val displayName: String,
    val description: String,
    val sourcePath: Path,
    val destinationPath: Path,
    val type: Type,
    val selected: Boolean,
    val sensitive: Boolean,
    val sessionId: String? = null,
    val title: String? = null,
    val lastModified: FileTime? = null,
    val sourceProjectPath: Path? = null,
    val destinationProjectPath: Path? = null
) {
    enum class Type {
        CLAUDE_PROJECT_CONTEXT,
        CLAUDE_SESSION_HISTORY
    }
}
