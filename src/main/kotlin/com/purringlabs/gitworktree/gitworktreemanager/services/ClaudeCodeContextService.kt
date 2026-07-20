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
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.math.absoluteValue

@Service(Service.Level.PROJECT)
class ClaudeCodeContextService(private val project: Project) {
    fun detectCopyOptions(
        sourceRepoPath: Path,
        destinationWorktreePath: Path,
        repoWorktreePaths: List<Path> = listOf(sourceRepoPath)
    ): List<AgentContextCopyOption> {
        return detectCopyOptions(
            sourceRepoPath = sourceRepoPath,
            destinationWorktreePath = destinationWorktreePath,
            claudeHome = defaultClaudeHome(),
            repoWorktreePaths = repoWorktreePaths
        )
    }

    fun detectCopyOptions(
        sourceRepoPath: Path,
        destinationWorktreePath: Path,
        claudeHome: Path,
        repoWorktreePaths: List<Path> = listOf(sourceRepoPath)
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

        val absoluteWorktreePaths = (repoWorktreePaths.map { it.toAbsolutePath().normalize() } + absoluteSourceRepoPath).distinct()
        val sessionDirPairs = absoluteWorktreePaths
            .map { worktreePath -> worktreePath to claudeProjectSessionPath(claudeHome, worktreePath) }
            .filter { (_, sessionDir) -> sessionDir.exists() && sessionDir.isDirectory() }
            .distinctBy { (_, sessionDir) -> sessionDir.normalize() }

        if (!isWindows() && sessionDirPairs.isNotEmpty()) {
            val destinationSessionDir = claudeProjectSessionPath(claudeHome, absoluteDestinationWorktreePath)
            val sessionFiles = sessionDirPairs.flatMap { (_, sessionDir) ->
                Files.list(sessionDir).use { entries ->
                    entries.filter { it.isRegularFile() && it.name.endsWith(".jsonl", ignoreCase = true) }
                        .toList()
                }
            }.distinctBy { it.normalize() }

            sessionFiles.mapNotNull { sessionFile ->
                val sessionId = sessionFile.nameWithoutExtension
                if (sessionId.isBlank()) return@mapNotNull null
                val metadata = sessionMetadata(sessionFile, sessionId)
                val modified = runCatching { Files.getLastModifiedTime(sessionFile) }.getOrNull()
                val dirWorktreePath = sessionDirPairs.firstOrNull { (_, sessionDir) ->
                    sessionFile.startsWith(sessionDir)
                }?.first
                val sourceProjectPath = metadata.cwd?.let { cwd ->
                    runCatching { Path.of(cwd).toAbsolutePath().normalize() }.getOrNull()
                } ?: dirWorktreePath ?: absoluteSourceRepoPath
                AgentContextCopyOption(
                    id = "claude-session-$sessionId",
                    displayName = metadata.title,
                    description = "Session $sessionId — may include prompts, code snippets, secrets, and local paths.",
                    sourcePath = sessionFile.normalize(),
                    destinationPath = destinationSessionDir.resolve(sessionFile.fileName).normalize(),
                    type = AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY,
                    selected = false,
                    sensitive = true,
                    sessionId = sessionId,
                    title = metadata.title,
                    lastModified = modified,
                    sourceProjectPath = sourceProjectPath,
                    destinationProjectPath = absoluteDestinationWorktreePath
                )
            }.sortedWith(
                compareByDescending<AgentContextCopyOption> { it.sourceProjectPath == absoluteSourceRepoPath }
                    .thenByDescending { it.lastModified?.toMillis() ?: Long.MIN_VALUE }
            ).forEach { options.add(it) }
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
        val newSessionId = generateSessionId()
        val destinationFile = option.destinationPath.parent.resolve("$newSessionId.jsonl")
        Files.createDirectories(option.destinationPath.parent)
        if (Files.exists(destinationFile)) {
            skipped.add(option.displayName to "Destination session file already exists")
        } else {
            runCatching {
                copySessionFile(option, newSessionId, destinationFile)
                copied.add(option.displayName)
            }.onFailure { failed.add(option.displayName to (it.message ?: it.javaClass.simpleName)) }
        }

        val sourceSessionId = option.sourcePath.nameWithoutExtension
        val companion = option.sourcePath.parent.resolve(sourceSessionId)
        if (Files.isDirectory(companion)) {
            val destinationCompanion = option.destinationPath.parent.resolve(newSessionId)
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

    private fun copySessionFile(option: AgentContextCopyOption, newSessionId: String, destinationFile: Path) {
        val sourceProjectPath = option.sourceProjectPath
        val destinationProjectPath = option.destinationProjectPath
        val sourceSessionId = option.sessionId
            ?: option.sourcePath.nameWithoutExtension

        if (sourceProjectPath == null || destinationProjectPath == null) {
            Files.copy(option.sourcePath, destinationFile, StandardCopyOption.COPY_ATTRIBUTES)
            return
        }

        // Claude's resume picker aggregates sessions across all worktrees of the repo and
        // dedupes by sessionId. Keeping the same id lets the original (often newer) file
        // shadow the copy, so the copied session must get its own id.
        Files.newBufferedReader(option.sourcePath).use { reader ->
            Files.newBufferedWriter(destinationFile).use { writer ->
                reader.forEachLine { line ->
                    val rewritten = runCatching {
                        rewriteSessionMetadata(
                            Json.parseToJsonElement(line),
                            sourceSessionId,
                            newSessionId,
                            sourceProjectPath,
                            destinationProjectPath
                        ).toString()
                    }.getOrNull() ?: line
                    writer.appendLine(rewritten)
                }
            }
        }
    }

    private fun rewriteSessionMetadata(
        element: JsonElement,
        sourceSessionId: String,
        newSessionId: String,
        sourceProjectPath: Path,
        destinationProjectPath: Path
    ): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            when {
                key == "cwd" && value is JsonPrimitive && value.contentOrNull == sourceProjectPath.toString() ->
                    JsonPrimitive(destinationProjectPath.toString())
                key == "sessionId" && value is JsonPrimitive && value.contentOrNull == sourceSessionId ->
                    JsonPrimitive(newSessionId)
                else ->
                    rewriteSessionMetadata(value, sourceSessionId, newSessionId, sourceProjectPath, destinationProjectPath)
            }
        })
        is JsonArray -> JsonArray(element.map {
            rewriteSessionMetadata(it, sourceSessionId, newSessionId, sourceProjectPath, destinationProjectPath)
        })
        else -> element
    }

    private fun generateSessionId(): String = UUID.randomUUID().toString()

    private fun sessionMetadata(file: Path, sessionId: String): SessionMetadata {
        var agentName: String? = null
        var customTitle: String? = null
        var aiTitle: String? = null
        var summary: String? = null
        var lastPrompt: String? = null
        var firstUserPrompt: String? = null
        var cwd: String? = null
        runCatching {
            Files.newBufferedReader(file).useLines { lines ->
                lines.forEach { line ->
                    val element = runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: return@forEach
                    val obj = element as? JsonObject ?: return@forEach
                    obj.string("agentName")?.cleanText()?.takeIf { it.isNotEmpty() }?.let { agentName = it }
                    obj.string("customTitle")?.cleanText()?.takeIf { it.isNotEmpty() }?.let { customTitle = it }
                    obj.string("aiTitle")?.cleanText()?.takeIf { it.isNotEmpty() }?.let { aiTitle = it }
                    obj.string("summary")?.cleanText()?.takeIf { it.isNotEmpty() }?.let { summary = it }
                    obj.string("lastPrompt")?.cleanText()?.takeIf { it.isNotEmpty() }?.let { lastPrompt = it }
                    if (cwd == null) obj.string("cwd")?.takeIf { it.isNotEmpty() }?.let { cwd = it }
                    if (firstUserPrompt == null && obj.string("type")?.lowercase() == "user") {
                        extractUserText(obj)?.cleanText()?.takeIf { it.isNotEmpty() }?.let { firstUserPrompt = it }
                    }
                }
            }
        }
        val title = (agentName ?: customTitle ?: aiTitle ?: summary ?: lastPrompt ?: firstUserPrompt ?: sessionId).truncateTitle()
        return SessionMetadata(title, cwd)
    }

    private data class SessionMetadata(val title: String, val cwd: String?)

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
            val configDir = System.getenv("CLAUDE_CONFIG_DIR")
            if (!configDir.isNullOrBlank()) {
                return Path.of(configDir).toAbsolutePath().normalize()
            }
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
            // Claude Code encodes the cwd by replacing every non-alphanumeric character with '-',
            // truncating to 200 characters, and appending a base36 hash if truncated.
            // Using toRealPath matches Claude's Sf(cwd) which calls realpath before encoding.
            val path = runCatching { projectPath.toRealPath() }.getOrDefault(
                projectPath.toAbsolutePath().normalize()
            )
            val pathString = path.toString()
            val sanitized = pathString.replace(Regex("[^A-Za-z0-9]"), "-")
            val maxSlugLength = 200
            if (sanitized.length <= maxSlugLength) {
                return sanitized
            }
            val hash = pathString.hashCode().toLong().absoluteValue
            val hashSuffix = java.lang.Long.toString(hash, 36)
            return "${sanitized.take(maxSlugLength)}-$hashSuffix"
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
