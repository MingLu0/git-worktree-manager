package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyOption
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

@Service(Service.Level.PROJECT)
class ClaudeCodeContextService(private val project: Project) {
    fun detectCopyOptions(sourceRepoPath: Path, destinationWorktreePath: Path): List<AgentContextCopyOption> {
        return detectCopyOptions(
            sourceRepoPath = sourceRepoPath,
            destinationWorktreePath = destinationWorktreePath,
            claudeHome = defaultClaudeHome()
        )
    }

    fun detectCopyOptions(
        sourceRepoPath: Path,
        destinationWorktreePath: Path,
        claudeHome: Path
    ): List<AgentContextCopyOption> {
        val options = mutableListOf<AgentContextCopyOption>()
        val sourceClaudeDir = sourceRepoPath.resolve(".claude").normalize()
        if (sourceClaudeDir.exists() && sourceClaudeDir.isDirectory()) {
            options.add(
                AgentContextCopyOption(
                    id = "claude-project-context",
                    displayName = "Claude Code project context (.claude/)",
                    description = "Copies shared Claude commands, agents, skills, and project guidance. Local/private files are excluded.",
                    sourcePath = sourceClaudeDir,
                    destinationPath = destinationWorktreePath.resolve(".claude").normalize(),
                    type = AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT,
                    selected = true,
                    sensitive = false
                )
            )
        }

        val sourceSessionDir = claudeProjectSessionPath(claudeHome, sourceRepoPath)
        if (!isWindows() && sourceSessionDir.exists() && sourceSessionDir.isDirectory()) {
            options.add(
                AgentContextCopyOption(
                    id = "claude-session-history",
                    displayName = "Claude Code session history",
                    description = "May include prompts, code snippets, secrets, and local paths. Copied only when explicitly selected.",
                    sourcePath = sourceSessionDir,
                    destinationPath = claudeProjectSessionPath(claudeHome, destinationWorktreePath),
                    type = AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY,
                    selected = false,
                    sensitive = true
                )
            )
        }

        return options
    }

    suspend fun copySelectedOptions(options: List<AgentContextCopyOption>): AgentContextCopyResult = withContext(Dispatchers.IO) {
        options.filter { it.selected }.fold(AgentContextCopyResult()) { accumulatedResult, option ->
            accumulatedResult.plus(copyOption(option))
        }
    }

    private fun copyOption(option: AgentContextCopyOption): AgentContextCopyResult {
        if (!Files.exists(option.sourcePath)) {
            return AgentContextCopyResult(skipped = listOf(option.displayName to "Source no longer exists"))
        }

        if (option.type == AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY && Files.exists(option.destinationPath)) {
            return AgentContextCopyResult(skipped = listOf(option.displayName to "Destination session history already exists"))
        }

        return try {
            if (option.type == AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT) {
                copyDirectorySkippingExisting(
                    option = option,
                    exclude = { relativePath -> isPrivateClaudeProjectPath(relativePath) }
                )
            } else {
                copyDirectorySkippingExisting(
                    option = option,
                    exclude = { false }
                )
            }
        } catch (e: Exception) {
            AgentContextCopyResult(failed = listOf(option.displayName to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun copyDirectorySkippingExisting(
        option: AgentContextCopyOption,
        exclude: (Path) -> Boolean
    ): AgentContextCopyResult {
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<Pair<String, String>>()
        val failed = mutableListOf<Pair<String, String>>()

        // Do not pass FOLLOW_LINKS. Claude context may contain local symlinks, and copying those could escape roots.
        Files.walkFileTree(option.sourcePath, object : FileVisitor<Path> {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relativePath = option.sourcePath.relativize(dir)
                if (relativePath.nameCount > 0 && exclude(relativePath)) return FileVisitResult.SKIP_SUBTREE
                val destinationDirectory = option.destinationPath.resolve(relativePath).normalize()
                if (!destinationDirectory.startsWith(option.destinationPath.normalize())) {
                    throw IOException("Refusing to copy outside destination root: $relativePath")
                }
                Files.createDirectories(destinationDirectory)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relativePath = option.sourcePath.relativize(file)
                if (!exclude(relativePath)) {
                    val destinationFile = option.destinationPath.resolve(relativePath)
                    if (!file.normalize().startsWith(option.sourcePath.normalize()) || !destinationFile.normalize().startsWith(option.destinationPath.normalize())) {
                        throw IOException("Refusing to copy outside allowed roots: $relativePath")
                    }
                    if (Files.exists(destinationFile)) {
                        skipped.add("${option.displayName}: $relativePath" to "Destination already exists")
                    } else {
                        Files.copy(file, destinationFile, StandardCopyOption.COPY_ATTRIBUTES)
                        copied.add("${option.displayName}: $relativePath")
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                val relativePath = runCatching { option.sourcePath.relativize(file).toString() }.getOrElse { file.toString() }
                failed.add("${option.displayName}: $relativePath" to (exc?.message ?: "Failed to visit file"))
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                return FileVisitResult.CONTINUE
            }
        })

        return AgentContextCopyResult(copied = copied, skipped = skipped, failed = failed)
    }

    companion object {
        fun getInstance(project: Project): ClaudeCodeContextService {
            return project.getService(ClaudeCodeContextService::class.java)
        }

        fun defaultClaudeHome(): Path {
            return Path.of(System.getProperty("user.home"), ".claude")
        }

        fun isWindows(): Boolean {
            return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        }

        fun claudeProjectSessionPath(claudeHome: Path, projectPath: Path): Path {
            return claudeHome.resolve("projects").resolve(claudeProjectKey(projectPath))
        }

        fun claudeProjectKey(projectPath: Path): String {
            // Claude Code currently keeps the leading separator as a leading '-': /Users/me/repo -> -Users-me-repo.
            return projectPath.toAbsolutePath().normalize().toString().replace('/', '-').replace('\\', '-')
        }

        fun isPrivateClaudeProjectPath(relativePath: Path): Boolean {
            val pathParts = (0 until relativePath.nameCount).map { index -> relativePath.getName(index).toString() }
            val fileName = relativePath.name
            if (pathParts.any { isPrivateName(it) }) return true
            if (fileName == "settings.local.json") return true
            return false
        }

        private val sensitiveNamePattern = Regex("(^|[._-])(local|private|secret|secrets|token|tokens|credential|credentials)([._-]|$)")

        private fun isPrivateName(name: String): Boolean {
            val lowerName = name.lowercase()
            if (lowerName == ".env" || lowerName.startsWith(".env.")) return true
            return sensitiveNamePattern.containsMatchIn(lowerName)
        }
    }
}
