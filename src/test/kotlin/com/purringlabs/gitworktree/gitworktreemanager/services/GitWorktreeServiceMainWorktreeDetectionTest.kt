package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class GitWorktreeServiceMainWorktreeDetectionTest : BasePlatformTestCase() {

    fun `test isMainWorktreePath returns true when dot-git is a directory`() {
        val dir = Files.createTempDirectory("gwt-main-").toFile()
        try {
            File(dir, ".git").mkdirs()

            val service = GitWorktreeService(project)
            assertTrue(service.isMainWorktreePath(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun `test isMainWorktreePath returns false when dot-git is a file`() {
        val dir = Files.createTempDirectory("gwt-linked-").toFile()
        try {
            File(dir, ".git").writeText("gitdir: /tmp/somewhere")

            val service = GitWorktreeService(project)
            assertFalse(service.isMainWorktreePath(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun `test isMainWorktreePath returns true when dot-git is missing (bare repo primary path)`() {
        val dir = Files.createTempDirectory("gwt-bare-").toFile()
        try {
            // no .git
            val service = GitWorktreeService(project)
            assertTrue(service.isMainWorktreePath(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun `test isMainWorktreePath returns false when worktree path does not exist`() {
        val dir = Files.createTempDirectory("gwt-missing-").toFile()
        try {
            val missingPath = File(dir, "does-not-exist").absolutePath
            val service = GitWorktreeService(project)
            assertFalse(service.isMainWorktreePath(missingPath))
        } finally {
            dir.deleteRecursively()
        }
    }
}
