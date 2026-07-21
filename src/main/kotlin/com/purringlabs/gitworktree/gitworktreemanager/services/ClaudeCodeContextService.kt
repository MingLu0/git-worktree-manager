package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.ClaudeSessionInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.math.absoluteValue

@Service(Service.Level.PROJECT)
class ClaudeCodeContextService(private val project: Project) {
    fun listSessions(repoWorktreePaths: List<Path>): List<ClaudeSessionInfo> =
        listSessions(defaultClaudeHome(), project.basePath?.let { Path.of(it) } ?: repoWorktreePaths.firstOrNull(), repoWorktreePaths)

    fun listSessions(
        claudeHome: Path,
        currentProjectPath: Path?,
        repoWorktreePaths: List<Path>
    ): List<ClaudeSessionInfo> {
        if (isWindows()) return emptyList()

        val currentPath = currentProjectPath?.toAbsolutePath()?.normalize()
        val absoluteWorktreePaths = (
            repoWorktreePaths.map { it.toAbsolutePath().normalize() } +
                listOfNotNull(currentPath)
            ).distinct()

        val sessionDirPairs = absoluteWorktreePaths
            .map { worktreePath -> worktreePath to claudeProjectSessionPath(claudeHome, worktreePath) }
            .filter { (_, sessionDir) -> sessionDir.exists() && sessionDir.isDirectory() }
            .distinctBy { (_, sessionDir) -> sessionDir.normalize() }

        return sessionDirPairs.flatMap { (_, sessionDir) ->
            Files.list(sessionDir).use { entries ->
                entries.filter { it.isRegularFile() && it.name.endsWith(".jsonl", ignoreCase = true) }
                    .toList()
            }
        }.distinctBy { it.normalize() }
            .mapNotNull { sessionFile ->
                val sessionId = sessionFile.nameWithoutExtension
                if (sessionId.isBlank()) return@mapNotNull null
                val metadata = sessionMetadata(sessionFile, sessionId)
                val modified = runCatching { Files.getLastModifiedTime(sessionFile) }.getOrNull()
                val dirWorktreePath = sessionDirPairs.firstOrNull { (_, sessionDir) ->
                    sessionFile.startsWith(sessionDir)
                }?.first
                val sourceProjectPath = metadata.cwd?.let { cwd ->
                    runCatching { Path.of(cwd).toAbsolutePath().normalize() }.getOrNull()
                } ?: dirWorktreePath ?: currentPath ?: return@mapNotNull null
                ClaudeSessionInfo(
                    sessionId = sessionId,
                    title = metadata.title,
                    lastModified = modified,
                    sourceProjectPath = sourceProjectPath,
                    sessionFile = sessionFile.normalize()
                )
            }
            .sortedWith(
                compareByDescending<ClaudeSessionInfo> { it.sourceProjectPath == currentPath }
                    .thenByDescending { it.lastModified?.toMillis() ?: Long.MIN_VALUE }
            )
    }

    private data class SessionMetadata(val title: String, val cwd: String?)

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
    }
}
