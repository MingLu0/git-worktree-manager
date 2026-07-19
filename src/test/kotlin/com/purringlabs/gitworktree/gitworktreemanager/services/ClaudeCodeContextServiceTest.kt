package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyOption
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeCodeContextServiceTest {
    @Test
    fun `detectCopyOptions selects project context and leaves session history unchecked`() {
        val tempDir = Files.createTempDirectory("claude-context-test")
        val sourceRepo = tempDir.resolve("repo").apply { createDirectories() }
        val destinationWorktree = tempDir.resolve("repo-feature")
        val claudeHome = tempDir.resolve("claude-home")
        sourceRepo.resolve(".claude").createDirectories()
        ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, sourceRepo).apply {
            createDirectories()
            resolve("session.jsonl").writeText("{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}")
        }

        val options = service().detectCopyOptions(sourceRepo, destinationWorktree, claudeHome)

        val projectContext = options.single { it.type == AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT }
        val sessionHistory = options.single { it.type == AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY }
        assertTrue(projectContext.selected)
        assertFalse(sessionHistory.selected)
        assertTrue(sessionHistory.sensitive)
    }

    @Test
    fun `detectCopyOptions creates one unchecked option per session with readable metadata`() {
        val tempDir = Files.createTempDirectory("claude-session-detection-test")
        val sourceRepo = tempDir.resolve("repo").apply { createDirectories() }
        val claudeHome = tempDir.resolve("claude-home")
        val sessions = ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, sourceRepo).apply { createDirectories() }
        sessions.resolve("summary-id.jsonl").writeText(
            """{"type":"summary","summary":"Preferred summary"}
{"type":"user","message":{"role":"user","content":"A later prompt"}}"""
        )
        sessions.resolve("prompt-id.jsonl").writeText(
            """not json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"First meaningful prompt"}]}}"""
        )
        sessions.resolve("empty-id.jsonl").writeText("{malformed}\n")

        val options = service().detectCopyOptions(sourceRepo, tempDir.resolve("worktree"), claudeHome)
        val sessionOptions = options.filter { it.type == AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY }

        assertEquals(setOf("summary-id", "prompt-id", "empty-id"), sessionOptions.mapNotNull { it.sessionId }.toSet())
        assertEquals("Preferred summary", sessionOptions.single { it.sessionId == "summary-id" }.title)
        assertEquals("First meaningful prompt", sessionOptions.single { it.sessionId == "prompt-id" }.title)
        assertEquals("empty-id", sessionOptions.single { it.sessionId == "empty-id" }.title)
        assertTrue(sessionOptions.all { !it.selected && it.lastModified != null })
        assertTrue(sessionOptions.all { it.sourcePath.isAbsolute && it.destinationPath.isAbsolute })
    }

    @Test
    fun `copySelectedOptions copies selected session and companion while merging destination slug`() = runBlocking {
        val tempDir = Files.createTempDirectory("claude-session-merge-test")
        val sourceRepo = tempDir.resolve("repo").apply { createDirectories() }
        val destinationRepo = tempDir.resolve("repo-feature")
        val claudeHome = tempDir.resolve("claude-home")
        val sourceSessions = ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, sourceRepo).apply { createDirectories() }
        val destinationSessions = ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, destinationRepo).apply { createDirectories() }

        sourceSessions.resolve("selected.jsonl").writeText("selected")
        sourceSessions.resolve("unselected.jsonl").writeText("unselected")
        sourceSessions.resolve("selected").createDirectories()
        sourceSessions.resolve("selected/context.txt").writeText("new companion data")
        destinationSessions.resolve("existing.jsonl").writeText("must remain")
        destinationSessions.resolve("selected").createDirectories()
        destinationSessions.resolve("selected/context.txt").writeText("must remain")

        val detected = service().detectCopyOptions(sourceRepo, destinationRepo, claudeHome)
        val selected = detected.single { it.sessionId == "selected" }.copy(selected = true)
        val unselected = detected.single { it.sessionId == "unselected" }
        val result = service().copySelectedOptions(listOf(selected, unselected))

        val copiedFile = copiedSessionFile(destinationSessions)
        val newSessionId = copiedFile.nameWithoutExtension
        assertTrue(isValidUuid(newSessionId))
        assertTrue(destinationSessions.resolve(newSessionId).resolve("context.txt").exists())
        assertFalse(destinationSessions.resolve("unselected.jsonl").exists())
        assertEquals("must remain", destinationSessions.resolve("existing.jsonl").readText())
        assertEquals("must remain", destinationSessions.resolve("selected").resolve("context.txt").readText())
        assertEquals("new companion data", destinationSessions.resolve(newSessionId).resolve("context.txt").readText())
        assertEquals(1, result.copied.count { it == "selected" })
    }

    @Test
    fun `copySelectedOptions rewrites session cwd and sessionId to destination worktree`() = runBlocking {
        val tempDir = Files.createTempDirectory("claude-session-cwd-test")
        val sourceRepo = tempDir.resolve("repo").apply { createDirectories() }
        val destinationRepo = tempDir.resolve("repo-feature")
        val claudeHome = tempDir.resolve("claude-home")
        val sourceSessions = ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, sourceRepo).apply { createDirectories() }
        sourceSessions.resolve("session.jsonl").writeText(
            """{"type":"user","cwd":"${sourceRepo.toAbsolutePath()}","sessionId":"session","message":{"content":"hello"}}"""
        )

        val option = service().detectCopyOptions(sourceRepo, destinationRepo, claudeHome)
            .single { it.sessionId == "session" }
            .copy(selected = true)
        val result = service().copySelectedOptions(listOf(option))
        val destinationFile = copiedSessionFile(option.destinationPath.parent)
        val newSessionId = destinationFile.nameWithoutExtension

        assertEquals(1, result.copiedCount)
        assertTrue(isValidUuid(newSessionId))
        val content = destinationFile.readText()
        assertTrue(content.contains("\"cwd\":\"${destinationRepo.toAbsolutePath()}\""))
        assertFalse(content.contains("\"cwd\":\"${sourceRepo.toAbsolutePath()}\""))
        assertTrue(content.contains("\"sessionId\":\"$newSessionId\""))
        assertFalse(content.contains("\"sessionId\":\"session\""))
    }

    @Test
    fun `copy failure for one session does not prevent another selected session`() = runBlocking {
        val tempDir = Files.createTempDirectory("claude-session-failure-test")
        val source = tempDir.resolve("source").apply { createDirectories() }
        val destination = tempDir.resolve("destination").apply { createDirectories() }
        val goodSource = source.resolve("good.jsonl").apply { writeText("good") }
        val badSource = source.resolve("bad.jsonl").apply { writeText("bad") }
        val blockedParent = destination.resolve("blocked").apply { writeText("not a directory") }

        fun option(id: String, sourcePath: Path, destinationPath: Path) = AgentContextCopyOption(
            id = "claude-session-$id",
            displayName = id,
            description = "",
            sourcePath = sourcePath,
            destinationPath = destinationPath,
            type = AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY,
            selected = true,
            sensitive = true,
            sessionId = id,
            title = id
        )

        val result = service().copySelectedOptions(
            listOf(
                option("bad", badSource, blockedParent.resolve("bad.jsonl")),
                option("good", goodSource, destination.resolve("good.jsonl"))
            )
        )

        assertTrue(result.hasFailures)
        assertTrue(copiedSessionFile(destination).readText().contains("good"))
        assertTrue(result.copied.any { it == "good" })
    }

    @Test
    fun `copySelectedOptions excludes local and private Claude project files`() = runBlocking {
        val tempDir = Files.createTempDirectory("claude-context-copy-test")
        val sourceClaude = tempDir.resolve("repo/.claude").apply { createDirectories() }
        val destinationClaude = tempDir.resolve("repo-feature/.claude")
        sourceClaude.resolve("commands").createDirectories()
        sourceClaude.resolve("commands/shared.md").writeText("shared")
        sourceClaude.resolve("settings.local.json").writeText("local")
        sourceClaude.resolve("notes.private.md").writeText("private")
        sourceClaude.resolve("secrets").createDirectories()
        sourceClaude.resolve("secrets/token.txt").writeText("token")

        val result = service().copySelectedOptions(
            listOf(
                AgentContextCopyOption(
                    id = "claude-project-context",
                    displayName = "Claude Code project context (.claude/)",
                    description = "",
                    sourcePath = sourceClaude,
                    destinationPath = destinationClaude,
                    type = AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT,
                    selected = true,
                    sensitive = false
                )
            )
        )

        assertEquals(1, result.copiedCount)
        assertEquals("shared", destinationClaude.resolve("commands/shared.md").readText())
        assertFalse(destinationClaude.resolve("settings.local.json").exists())
        assertFalse(destinationClaude.resolve("notes.private.md").exists())
        assertFalse(destinationClaude.resolve("secrets/token.txt").exists())
    }

    @Test
    fun `copySelectedOptions returns empty result when all options are unselected`() = runBlocking {
        val tempDir = Files.createTempDirectory("claude-unselected-test")
        val sourceClaude = tempDir.resolve("repo/.claude").apply { createDirectories() }
        val destinationClaude = tempDir.resolve("repo-feature/.claude")
        sourceClaude.resolve("commands").createDirectories()
        sourceClaude.resolve("commands/review.md").writeText("source")

        val result = service().copySelectedOptions(
            listOf(
                AgentContextCopyOption(
                    id = "claude-project-context",
                    displayName = "Claude Code project context (.claude/)",
                    description = "",
                    sourcePath = sourceClaude,
                    destinationPath = destinationClaude,
                    type = AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT,
                    selected = false,
                    sensitive = false
                )
            )
        )

        assertFalse(result.hasEntries)
        assertFalse(destinationClaude.exists())
    }

    @Test
    fun `copySelectedOptions skips option when source no longer exists`() = runBlocking {
        val tempDir = Files.createTempDirectory("claude-missing-source-test")
        val missingSource = tempDir.resolve("missing/.claude")

        val result = service().copySelectedOptions(
            listOf(
                AgentContextCopyOption(
                    id = "claude-project-context",
                    displayName = "Claude Code project context (.claude/)",
                    description = "",
                    sourcePath = missingSource,
                    destinationPath = tempDir.resolve("repo-feature/.claude"),
                    type = AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT,
                    selected = true,
                    sensitive = false
                )
            )
        )

        assertEquals(0, result.copiedCount)
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun `copySelectedOptions skips existing Claude project context files`() = runBlocking {
        val tempDir = Files.createTempDirectory("claude-existing-context-test")
        val sourceClaude = tempDir.resolve("repo/.claude").apply { createDirectories() }
        val destinationClaude = tempDir.resolve("repo-feature/.claude").apply { createDirectories() }
        sourceClaude.resolve("commands").createDirectories()
        destinationClaude.resolve("commands").createDirectories()
        sourceClaude.resolve("commands/review.md").writeText("source")
        destinationClaude.resolve("commands/review.md").writeText("existing")

        val result = service().copySelectedOptions(
            listOf(
                AgentContextCopyOption(
                    id = "claude-project-context",
                    displayName = "Claude Code project context (.claude/)",
                    description = "",
                    sourcePath = sourceClaude,
                    destinationPath = destinationClaude,
                    type = AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT,
                    selected = true,
                    sensitive = false
                )
            )
        )

        assertEquals(0, result.copiedCount)
        assertEquals(1, result.skippedCount)
        assertEquals("existing", destinationClaude.resolve("commands/review.md").readText())
    }

    @Test
    fun `private Claude path matcher excludes expected names`() {
        assertTrue(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of("settings.local.json")))
        assertTrue(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of("foo.secret.json")))
        assertTrue(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of("secrets", "token.txt")))
        assertTrue(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of("my-secrets", "value.txt")))
        assertTrue(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of("api-credentials", "value.txt")))
        assertTrue(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of(".env", "token.txt")))
        assertTrue(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of(".env.local")))
        assertFalse(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of("commands", "review.md")))
        assertFalse(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of("commands", "tokenize.md")))
        assertFalse(ClaudeCodeContextService.isPrivateClaudeProjectPath(Path.of("authenticate-locally.md")))
    }

    @Test
    fun `claudeProjectKey matches Claude Code encoding for leading separator and dots`() {
        val key = ClaudeCodeContextService.claudeProjectKey(Path.of("/Users/ming/project.v1_test"))

        assertEquals("-Users-ming-project-v1-test", key)
    }

    @Test
    fun `claudeProjectKey truncates long paths with a base36 hash suffix`() {
        val longPath = Path.of("/Users/ming/" + "a".repeat(250))
        val key = ClaudeCodeContextService.claudeProjectKey(longPath)

        assertTrue(key.startsWith("-Users-ming-" + "a".repeat(188) + "-"))
        assertTrue(key.length > 200)
        // Suffix after the last dash must be base36 alphanumeric.
        val suffix = key.substringAfterLast("-")
        assertTrue(suffix.matches(Regex("^[0-9a-z]+$")))
    }

    @Test
    fun `defaultClaudeHome falls back to user home when CLAUDE_CONFIG_DIR is unset`() {
        assertEquals(Path.of(System.getProperty("user.home"), ".claude"), ClaudeCodeContextService.defaultClaudeHome())
    }

    @Test
    fun `claudeProjectSessionPath normalizes home and project paths`() {
        val path = ClaudeCodeContextService.claudeProjectSessionPath(
            Path.of("relative-claude-home/../claude-home"),
            Path.of("relative-repo/../repo")
        )

        assertTrue(path.isAbsolute)
        assertTrue(path.endsWith("projects/${ClaudeCodeContextService.claudeProjectKey(Path.of("relative-repo/../repo"))}"))
    }

    private fun copiedSessionFile(dir: Path): Path {
        return Files.list(dir).use { entries ->
            entries.filter { it.isRegularFile() && it.toString().endsWith(".jsonl") && isValidUuid(it.nameWithoutExtension) }
                .toList()
                .singleOrNull()
                ?: throw AssertionError("Expected exactly one UUID-named .jsonl session file in $dir")
        }
    }

    private fun isValidUuid(value: String): Boolean {
        return try {
            UUID.fromString(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun service(): ClaudeCodeContextService = ClaudeCodeContextService(fakeProject())

    private fun fakeProject(): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as Project
    }
}
