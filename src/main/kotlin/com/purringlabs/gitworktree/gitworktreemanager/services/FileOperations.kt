package com.purringlabs.gitworktree.gitworktreemanager.services

import com.purringlabs.gitworktree.gitworktreemanager.models.CopyResult
import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo
import java.nio.file.Path

interface FileOperations {
    suspend fun copyItems(
        sourceRoot: Path,
        destRoot: Path,
        items: List<IgnoredFileInfo>
    ): CopyResult
}
