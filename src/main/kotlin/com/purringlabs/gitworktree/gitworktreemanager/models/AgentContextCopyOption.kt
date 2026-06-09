package com.purringlabs.gitworktree.gitworktreemanager.models

import java.nio.file.Path

data class AgentContextCopyOption(
    val id: String,
    val displayName: String,
    val description: String,
    val sourcePath: Path,
    val destinationPath: Path,
    val type: Type,
    val selected: Boolean,
    val sensitive: Boolean
) {
    enum class Type {
        CLAUDE_PROJECT_CONTEXT,
        CLAUDE_SESSION_HISTORY
    }
}
