package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.ClaudeSessionInfo
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeCodeContextServiceTest {
    @Test
    fun `listSessions returns one item per session with readable metadata`() {
        val tempDir = Files.createTempDirectory("claude-session-list-test")
        val sourceRepo = tempDir.resolve("repo").apply { createDirectories() }
        val claudeHome = tempDir.resolve("claude-home")
        val sessions = ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, sourceRepo).apply { createDirectories() }
        sessions.resolve("summary-id.jsonl").writeText(
            """{"type":"summary","summary":"Preferred summary"}
{"type":"user","message":{"role":"user","content":"A later prompt"}}"""
        )
        sessions.resolve("ai-title-id.jsonl").writeText(
            """{"type":"user","message":{"role":"user","content":"First prompt"}}
{"type":"ai-title","aiTitle":"AI title","timestamp":123}"""
        )
        sessions.resolve("last-prompt-id.jsonl").writeText(
            """{"type":"user","message":{"role":"user","content":"First prompt"}}
{"type":"last-prompt","lastPrompt":"Last prompt text","timestamp":456}"""
        )
        sessions.resolve("priority-id.jsonl").writeText(
            """{"type":"user","message":{"role":"user","content":"First prompt"}}
{"type":"last-prompt","lastPrompt":"Last prompt text","timestamp":1}
{"type":"summary","summary":"Summary text","timestamp":2}
{"type":"ai-title","aiTitle":"AI title","timestamp":3}
{"type":"custom-title","customTitle":"Custom Title","timestamp":4}"""
        )
        sessions.resolve("prompt-id.jsonl").writeText(
            """not json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"First meaningful prompt"}]}}"""
        )
        sessions.resolve("empty-id.jsonl").writeText("{malformed}\n")

        val result = service().listSessions(claudeHome, sourceRepo, listOf(sourceRepo))

        assertEquals(
            setOf("summary-id", "ai-title-id", "last-prompt-id", "priority-id", "prompt-id", "empty-id"),
            result.map { it.sessionId }.toSet()
        )
        assertEquals("Preferred summary", result.single { it.sessionId == "summary-id" }.title)
        assertEquals("AI title", result.single { it.sessionId == "ai-title-id" }.title)
        assertEquals("Last prompt text", result.single { it.sessionId == "last-prompt-id" }.title)
        assertEquals("Custom Title", result.single { it.sessionId == "priority-id" }.title)
        assertEquals("First meaningful prompt", result.single { it.sessionId == "prompt-id" }.title)
        assertEquals("empty-id", result.single { it.sessionId == "empty-id" }.title)
        assertTrue(result.all { it.lastModified != null })
        assertTrue(result.all { it.sourceProjectPath.isAbsolute })
        assertTrue(result.all { it.sessionFile.isAbsolute })
    }

    @Test
    fun `listSessions lists sessions from all provided repo worktrees`() {
        val tempDir = Files.createTempDirectory("claude-multi-worktree-test")
        val sourceRepo = tempDir.resolve("repo").apply { createDirectories() }
        val otherWorktree = tempDir.resolve("repo-feature")
        val claudeHome = tempDir.resolve("claude-home")

        val repoSessions = ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, sourceRepo).apply { createDirectories() }
        repoSessions.resolve("main-session.jsonl").writeText(
            """{"type":"summary","summary":"Main session"}
{"type":"user","cwd":"${sourceRepo.toAbsolutePath()}","message":{"content":"prompt"}}"""
        )

        val otherSessions = ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, otherWorktree).apply { createDirectories() }
        otherSessions.resolve("other-session.jsonl").writeText(
            """{"type":"ai-title","aiTitle":"Other session","cwd":"${otherWorktree.toAbsolutePath()}","timestamp":1}
{"type":"user","cwd":"${otherWorktree.toAbsolutePath()}","message":{"content":"prompt"}}"""
        )

        val result = service().listSessions(
            currentProjectPath = sourceRepo,
            repoWorktreePaths = listOf(sourceRepo, otherWorktree),
            claudeHome = claudeHome
        )

        assertEquals(listOf("main-session", "other-session"), result.map { it.sessionId })
        assertEquals("Main session", result[0].title)
        assertEquals("Other session", result[1].title)
        assertEquals(sourceRepo.toAbsolutePath().normalize(), result[0].sourceProjectPath)
        assertEquals(otherWorktree.toAbsolutePath().normalize(), result[1].sourceProjectPath)
    }

    @Test
    fun `listSessions defaults to current worktree sessions only`() {
        val tempDir = Files.createTempDirectory("claude-default-worktree-test")
        val sourceRepo = tempDir.resolve("repo").apply { createDirectories() }
        val otherWorktree = tempDir.resolve("repo-feature")
        val claudeHome = tempDir.resolve("claude-home")

        ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, sourceRepo).apply { createDirectories() }
            .resolve("main-session.jsonl").writeText("""{"type":"summary","summary":"Main"}""")
        ClaudeCodeContextService.claudeProjectSessionPath(claudeHome, otherWorktree).apply { createDirectories() }
            .resolve("other-session.jsonl").writeText("""{"type":"summary","summary":"Other"}""")

        val result = service().listSessions(claudeHome, sourceRepo, listOf(sourceRepo))

        assertEquals(setOf("main-session"), result.map { it.sessionId }.toSet())
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
