package com.purringlabs.gitworktree.gitworktreemanager.services

import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo

interface IgnoredFilesScanner {
    suspend fun scanIgnoredFiles(projectPath: String): Result<List<IgnoredFileInfo>>
}
