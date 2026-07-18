package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyOption
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

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
        val absoluteSourceRepoPath = sourceRepoPath.toAbsolutePath().normalize()
        val absoluteDestinationWorktreePath = destinationWorktreePath.toAbsolutePath().normalize()
        val sourceClaudeDir = absoluteSourceRepoPath.resolve(".claude").normalize()
        if (sourceClaudeDir.exists() && sourceClaudeDir.isDirectory()) {
            options.add(
                AgentContextCopyOption(
                    id = "claude-project-context",
                    displayName = "Claude Code project context (.claude/)",
                    description = "Copies shared Claude commands, agents, skills, and project guidance. Local/private files are excluded.",
                    sourcePath = sourceClaudeDir,
                    destinationPath = absoluteDestinationWorktreePath.resolve(".claude").normalize(),
                    type = AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT,
                    selected = true,
                    sensitive = false
                )
            )
        }

        val sourceSessionDir = claudeProjectSessionPath(claudeHome, absoluteSourceRepoPath)
        if (!isWindows() && sourceSessionDir.exists() && sourceSessionDir.isDirectory()) {
            val destinationSessionDir = claudeProjectSessionPath(claudeHome, absoluteDestinationWorktreePath)
            Files.list(sourceSessionDir).use { entries ->
                entries.filter { it.isRegularFile() && it.name.endsWith(".jsonl", ignoreCase = true) }
                    .sorted(compareByDescending { runCatching { Files.getLastModifiedTime(it) }.getOrNull() })
                    .forEach { sessionFile ->
                        val sessionId = sessionFile.nameWithoutExtension
                        val title = sessionTitle(sessionFile, sessionId)
                        val modified = runCatching { Files.getLastModifiedTime(sessionFile) }.getOrNull()
                        options.add(
                            AgentContextCopyOption(
                                id = "claude-session-$sessionId",
                                displayName = title,
                                description = "Session $sessionId — may include prompts, code snippets, secrets, and local paths.",
                                sourcePath = sessionFile.normalize(),
                                destinationPath = destinationSessionDir.resolve(sessionFile.fileName).normalize(),
                                type = AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY,
                                selected = false,
                                sensitive = true,
                                sessionId = sessionId,
                                title = title,
                                lastModified = modified,
                                sourceProjectPath = absoluteSourceRepoPath,
                                destinationProjectPath = absoluteDestinationWorktreePath
                            )
                        )
                    }
            }
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

        return try {
            if (option.type == AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT) {
                copyDirectorySkippingExisting(
                    option = option,
                    exclude = { relativePath -> isPrivateClaudeProjectPath(relativePath) }
                )
            } else if (option.sessionId != null && Files.isRegularFile(option.sourcePath)) {
                copySession(option)
            } else {
                // Compatibility for callers that still construct the old whole-directory option.
                copyDirectorySkippingExisting(option = option, exclude = { false })
            }
        } catch (e: Exception) {
            AgentContextCopyResult(failed = listOf(option.displayName to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun copySession(option: AgentContextCopyOption): AgentContextCopyResult {
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<Pair<String, String>>()
        val failed = mutableListOf<Pair<String, String>>()
        Files.createDirectories(option.destinationPath.parent)
        if (Files.exists(option.destinationPath)) {
            skipped.add(option.displayName to "Destination session file already exists")
        } else {
            runCatching {
                copySessionFile(option)
                copied.add(option.displayName)
            }.onFailure { failed.add(option.displayName to (it.message ?: it.javaClass.simpleName)) }
        }

        val companion = option.sourcePath.parent.resolve(option.sourcePath.nameWithoutExtension)
        if (Files.isDirectory(companion)) {
            val destinationCompanion = option.destinationPath.parent.resolve(companion.fileName.toString())
            val companionResult = copyDirectorySkippingExisting(
                option.copy(sourcePath = companion, destinationPath = destinationCompanion),
                exclude = { false }
            )
            copied += companionResult.copied
            skipped += companionResult.skipped
            failed += companionResult.failed
        }
        return AgentContextCopyResult(copied, skipped, failed)
    }

    private fun copySessionFile(option: AgentContextCopyOption) {
        val sourceProjectPath = option.sourceProjectPath
        val destinationProjectPath = option.destinationProjectPath
        if (sourceProjectPath == null || destinationProjectPath == null) {
            Files.copy(option.sourcePath, option.destinationPath, StandardCopyOption.COPY_ATTRIBUTES)
            return
        }

        // Claude filters /resume results using the cwd recorded in the transcript,
        // not only the slug directory. Rewrite only structured cwd fields so copied
        // sessions are recognized as belonging to the new worktree.
        Files.newBufferedReader(option.sourcePath).use { reader ->
            Files.newBufferedWriter(option.destinationPath).use { writer ->
                reader.forEachLine { line ->
                    val rewritten = runCatching {
                        rewriteCwd(Json.parseToJsonElement(line), sourceProjectPath, destinationProjectPath).toString()
                    }.getOrNull() ?: line
                    writer.appendLine(rewritten)
                }
            }
        }
        Files.setLastModifiedTime(option.destinationPath, Files.getLastModifiedTime(option.sourcePath))
    }

    private fun rewriteCwd(element: JsonElement, sourceProjectPath: Path, destinationProjectPath: Path): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key == "cwd" && value is JsonPrimitive && value.contentOrNull == sourceProjectPath.toString()) {
                JsonPrimitive(destinationProjectPath.toString())
            } else {
                rewriteCwd(value, sourceProjectPath, destinationProjectPath)
            }
        })
        is JsonArray -> JsonArray(element.map { rewriteCwd(it, sourceProjectPath, destinationProjectPath) })
        else -> element
    }

    private fun sessionTitle(file: Path, sessionId: String): String {
        val userPrompts = mutableListOf<String>()
        var providedTitle: String? = null
        runCatching {
            Files.newBufferedReader(file).useLines { lines ->
                lines.forEach { line ->
                    val element = runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: return@forEach
                    val obj = element as? JsonObject
                    val type = obj?.string("type")?.lowercase()
                    if (type == "summary" || type == "custom-title" || type == "title") {
                        val candidate = (obj.string("summary") ?: obj.string("title") ?: obj.string("customTitle"))
                            ?.cleanText()
                            ?.takeIf { it.isNotEmpty() }
                        if (providedTitle == null) providedTitle = candidate
                    }
                    if (type == "user") extractUserText(obj)?.cleanText()?.takeIf { it.isNotEmpty() }?.let(userPrompts::add)
                }
            }
        }
        return (providedTitle ?: userPrompts.firstOrNull() ?: sessionId).truncateTitle()
    }

    private fun extractUserText(obj: JsonObject?): String? {
        val message = obj?.get("message")
        return when (message) {
            is JsonObject -> textFromElement(message["content"])
            else -> textFromElement(message) ?: obj?.string("content")
        }
    }

    private fun textFromElement(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element["text"]?.let(::textFromElement) ?: element["content"]?.let(::textFromElement)
        is JsonArray -> element.mapNotNull(::textFromElement).joinToString(" ").takeIf { it.isNotBlank() }
        else -> null
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun String.cleanText(): String = replace(Regex("\\s+"), " ").trim()
    private fun String.truncateTitle(): String = if (length > 100) take(97).trimEnd() + "…" else this

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
            return claudeHome.toAbsolutePath().normalize()
                .resolve("projects")
                .resolve(claudeProjectKey(projectPath))
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
