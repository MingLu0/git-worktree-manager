package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.CopyResult
import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepositoryContract
import com.purringlabs.gitworktree.gitworktreemanager.services.FileOperations
import com.purringlabs.gitworktree.gitworktreemanager.services.IgnoredFilesScanner
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorktreeViewModelDeletePreconditionsTest {

    @Test
    fun `deleteWorktree rejects locked worktree`() = runBlocking {
        val repo = FakeRepo()
        val vm = WorktreeViewModel(fakeProject(), this, repo, FakeScanner, FakeFileOps)
        vm.state = vm.state.copy(
            worktrees = listOf(
                WorktreeInfo(
                    path = "/tmp/locked",
                    commit = "deadbeef",
                    branch = "feature/locked",
                    isLocked = true,
                    lockReason = "portable disk"
                )
            )
        )

        val errors = mutableListOf<String>()
        vm.deleteWorktree("/tmp/locked", onSuccess = {}, onError = { errors += (it.message ?: "") })
        kotlinx.coroutines.yield()

        assertTrue(errors.any { it.contains("Cannot delete locked worktree") })
        assertTrue(errors.any { it.contains("portable disk") })
        assertEquals(0, repo.deleteCalls)
    }

    @Test
    fun `deleteWorktree rejects prunable worktree`() = runBlocking {
        val repo = FakeRepo()
        val vm = WorktreeViewModel(fakeProject(), this, repo, FakeScanner, FakeFileOps)
        vm.state = vm.state.copy(
            worktrees = listOf(
                WorktreeInfo(
                    path = "/tmp/prunable",
                    commit = "deadbeef",
                    branch = "feature/prunable",
                    isPrunable = true,
                    prunableReason = "gitdir file points to non-existent location"
                )
            )
        )

        val errors = mutableListOf<String>()
        vm.deleteWorktree("/tmp/prunable", onSuccess = {}, onError = { errors += (it.message ?: "") })
        kotlinx.coroutines.yield()

        assertTrue(errors.any { it.contains("git worktree prune") })
        assertTrue(errors.any { it.contains("gitdir file points to non-existent location") })
        assertEquals(0, repo.deleteCalls)
    }

    private class FakeRepo : WorktreeRepositoryContract {
        var deleteCalls: Int = 0
        override suspend fun fetchWorktrees() = Result.success(emptyList<WorktreeInfo>())
        override suspend fun createWorktree(name: String, branchName: String, createNewBranch: Boolean) =
            Result.failure<com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult>(IllegalStateException("unused"))

        override suspend fun deleteWorktree(worktreePath: String, branchName: String?) =
            Result.success(com.purringlabs.gitworktree.gitworktreemanager.models.DeleteWorktreeResult(true, true)).also {
                deleteCalls++
            }
    }

    private object FakeScanner : IgnoredFilesScanner {
        override suspend fun scanIgnoredFiles(projectPath: String) = Result.success(emptyList<IgnoredFileInfo>())
    }

    private object FakeFileOps : FileOperations {
        override suspend fun copyItems(sourceRoot: Path, destRoot: Path, items: List<IgnoredFileInfo>) =
            CopyResult(succeeded = emptyList(), failed = emptyList())
    }

    private fun fakeProject(basePath: String = "/tmp"): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Short.TYPE -> 0.toShort()
                    java.lang.Byte.TYPE -> 0.toByte()
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Project
    }
}
