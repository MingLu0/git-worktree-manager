package com.purringlabs.gitworktree.gitworktreemanager.services

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GitWorktreeServiceBranchCommandDirectoryTest {

    private lateinit var temporaryDirectory: File
    private lateinit var mainWorktreePath: String
    private lateinit var removedWorktreePath: String
    private lateinit var otherWorktreePath: String

    @BeforeTest
    fun createWorktreeDirectories() {
        temporaryDirectory = Files.createTempDirectory("gwt-branch-dir-").toFile()
        mainWorktreePath = File(temporaryDirectory, "app").apply { mkdirs() }.absolutePath
        removedWorktreePath = File(temporaryDirectory, "app-feature").apply { mkdirs() }.absolutePath
        otherWorktreePath = File(temporaryDirectory, "app-other").apply { mkdirs() }.absolutePath
    }

    @AfterTest
    fun deleteWorktreeDirectories() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun `keeps repository root when it is not the removed worktree`() {
        val selectedDirectory = GitWorktreeService.selectBranchCommandDirectory(
            repositoryRootPath = mainWorktreePath,
            removedWorktreePath = removedWorktreePath,
            knownWorktreePaths = listOf(mainWorktreePath, removedWorktreePath)
        )

        assertEquals(mainWorktreePath, selectedDirectory)
    }

    @Test
    fun `falls back to main worktree when repository root is the removed worktree`() {
        val selectedDirectory = GitWorktreeService.selectBranchCommandDirectory(
            repositoryRootPath = removedWorktreePath,
            removedWorktreePath = removedWorktreePath,
            knownWorktreePaths = listOf(mainWorktreePath, removedWorktreePath, otherWorktreePath)
        )

        assertEquals(mainWorktreePath, selectedDirectory)
    }

    @Test
    fun `falls back when repository root is nested inside the removed worktree`() {
        val nestedRepositoryRoot = File(removedWorktreePath, "module").apply { mkdirs() }.absolutePath

        val selectedDirectory = GitWorktreeService.selectBranchCommandDirectory(
            repositoryRootPath = nestedRepositoryRoot,
            removedWorktreePath = removedWorktreePath,
            knownWorktreePaths = listOf(mainWorktreePath, removedWorktreePath)
        )

        assertEquals(mainWorktreePath, selectedDirectory)
    }

    @Test
    fun `skips worktrees whose directory no longer exists`() {
        val missingWorktreePath = File(temporaryDirectory, "app-missing").absolutePath

        val selectedDirectory = GitWorktreeService.selectBranchCommandDirectory(
            repositoryRootPath = removedWorktreePath,
            removedWorktreePath = removedWorktreePath,
            knownWorktreePaths = listOf(missingWorktreePath, removedWorktreePath, otherWorktreePath)
        )

        assertEquals(otherWorktreePath, selectedDirectory)
    }

    @Test
    fun `does not treat a sibling with a shared name prefix as inside the removed worktree`() {
        val siblingWithPrefix = File(temporaryDirectory, "app-feature-2").apply { mkdirs() }.absolutePath

        val selectedDirectory = GitWorktreeService.selectBranchCommandDirectory(
            repositoryRootPath = siblingWithPrefix,
            removedWorktreePath = removedWorktreePath,
            knownWorktreePaths = listOf(mainWorktreePath)
        )

        assertEquals(siblingWithPrefix, selectedDirectory)
    }
}
