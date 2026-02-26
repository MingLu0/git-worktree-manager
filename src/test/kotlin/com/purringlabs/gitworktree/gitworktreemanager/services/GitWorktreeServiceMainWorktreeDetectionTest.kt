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
}
