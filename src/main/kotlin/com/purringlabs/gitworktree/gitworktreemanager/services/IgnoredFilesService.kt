package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

/**
 * Service for detecting files and directories ignored by .gitignore
 *
 * Uses `git status --ignored --porcelain=v2` to accurately identify ignored files,
 * respecting all .gitignore rules including nested .gitignore files and global Git ignore rules.
 *
 * Requires Git 2.11.0+ for --porcelain=v2 support.
 */
@Service(Service.Level.PROJECT)
class IgnoredFilesService(private val project: Project) : IgnoredFilesScanner {

    companion object {
        fun getInstance(project: Project): IgnoredFilesService {
            return project.getService(IgnoredFilesService::class.java)
        }
    }

    /**
     * Scans the project for files and directories ignored by .gitignore
     *
     * @param projectPath The absolute path to the project root
     * @return Result containing list of IgnoredFileInfo objects, or error if scan fails
     */
    override suspend fun scanIgnoredFiles(projectPath: String): Result<List<IgnoredFileInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val repositoryManager = GitRepositoryManager.getInstance(project)

            val repository = repositoryManager.repositories.firstOrNull()
                ?: return@runCatching emptyList<IgnoredFileInfo>()

            val git = Git.getInstance()
            val handler = GitLineHandler(project, repository.root, GitCommand.STATUS)
            handler.addParameters("--ignored")
            handler.addParameters("--porcelain=v2")

            val result = git.runCommand(handler)

            if (!result.success()) {
                throw Exception("Git command failed: ${result.errorOutputAsJoinedString}")
            }

            val ignoredPaths = parseIgnoredFiles(result.output)
            val projectRoot = Paths.get(projectPath)

            ignoredPaths.mapNotNull { relativePath ->
                val absolutePath = projectRoot.resolve(relativePath)
                if (!Files.exists(absolutePath)) {
                    null // File disappeared between scan and stat
                } else {
                    val isDir = Files.isDirectory(absolutePath)
                    val size = if (isDir) null else {
                        try {
                            Files.size(absolutePath)
                        } catch (e: Exception) {
                            null // Can't read size (permission issue, etc.)
                        }
                    }

                    IgnoredFileInfo(
                        relativePath = relativePath,
                        type = if (isDir) IgnoredFileInfo.FileType.DIRECTORY else IgnoredFileInfo.FileType.FILE,
                        sizeBytes = size,
                        selected = false
                    )
                }
            }
        }
    }

    /**
     * Parses the output of `git status --ignored --porcelain=v2`
     *
     * Lines starting with "! " indicate ignored files/directories
     *
     * @param output The git command output lines
     * @return List of relative paths to ignored files
     */
    private fun parseIgnoredFiles(output: List<String>): List<String> {
        return output
            .filter { it.startsWith("! ") }
            .map { line ->
                // Format: "! <path>"
                line.substring(2).trim()
            }
            .filter { it.isNotEmpty() }
    }
}
