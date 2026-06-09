package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyOption
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
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
        ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, sourceRepo).createDirectories()

        val options = service().detectCopyOptions(sourceRepo, destinationWorktree, claudeHome)

        val projectContext = options.single { it.type == AgentContextCopyOption.Type.CLAUDE_PROJECT_CONTEXT }
        val sessionHistory = options.single { it.type == AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY }
        assertTrue(projectContext.selected)
        assertFalse(sessionHistory.selected)
        assertTrue(sessionHistory.sensitive)
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
    fun `copySelectedOptions skips session history when destination exists`() = runBlocking {
        val tempDir = Files.createTempDirectory("claude-session-copy-test")
        val sourceSession = tempDir.resolve("source-session").apply { createDirectories() }
        val destinationSession = tempDir.resolve("destination-session").apply { createDirectories() }
        sourceSession.resolve("conversation.jsonl").writeText("source")
        destinationSession.resolve("conversation.jsonl").writeText("existing")

        val result = service().copySelectedOptions(
            listOf(
                AgentContextCopyOption(
                    id = "claude-session-history",
                    displayName = "Claude Code session history",
                    description = "",
                    sourcePath = sourceSession,
                    destinationPath = destinationSession,
                    type = AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY,
                    selected = true,
                    sensitive = true
                )
            )
        )

        assertEquals(0, result.copiedCount)
        assertEquals(1, result.skippedCount)
        assertEquals("existing", destinationSession.resolve("conversation.jsonl").readText())
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
    fun `claudeProjectKey keeps leading absolute path separator as hyphen`() {
        val key = ClaudeCodeContextService.claudeProjectKey(Path.of("/Users/ming/project"))

        assertEquals("-Users-ming-project", key)
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
