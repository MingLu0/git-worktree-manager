package com.purringlabs.gitworktree.gitworktreemanager.models

import java.nio.file.Path
import java.nio.file.attribute.FileTime

data class ClaudeSessionInfo(
    val sessionId: String,
    val title: String,
    val lastModified: FileTime?,
    val sourceProjectPath: Path,
    val sessionFile: Path
)
