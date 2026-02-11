package com.purringlabs.gitworktree.gitworktreemanager.viewmodel

import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.CopyResult
import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepositoryContract
import com.purringlabs.gitworktree.gitworktreemanager.services.FileOperations
import com.purringlabs.gitworktree.gitworktreemanager.services.IgnoredFilesScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeViewModelProgressTest {
    @Test
    fun `createWorktree toggles isCreating during operation`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeWorktreeRepository(
            createHandler = {
                gate.await()
                Result.success(com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult(path = "/tmp/worktree", created = true))
            }
        )
        val viewModel = WorktreeViewModel(
            project = fakeProject(),
            coroutineScope = this,
            repository = repository,
            ignoredFilesService = FakeIgnoredFilesScanner(),
            fileOpsService = FakeFileOperations
        )

        viewModel.createWorktree(
            name = "feature",
            branchName = "feature",
            onSuccess = {},
            onError = {}
        )

        withTimeout(500) {
            while (!viewModel.state.isCreating) {
                delay(5)
            }
        }
        assertTrue(viewModel.state.isCreating)

        gate.complete(Unit)

        withTimeout(500) {
            while (viewModel.state.isCreating) {
                delay(5)
            }
        }
        assertFalse(viewModel.state.isCreating)
    }

    @Test
    fun `deleteWorktree toggles deletingWorktreePath during operation`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeWorktreeRepository(
            deleteHandler = {
                gate.await()
                Result.success(Unit)
            }
        )
        val viewModel = WorktreeViewModel(
            project = fakeProject(),
            coroutineScope = this,
            repository = repository,
            ignoredFilesService = FakeIgnoredFilesScanner(),
            fileOpsService = FakeFileOperations
        )

        viewModel.deleteWorktree(
            worktreePath = "/tmp/worktree",
            onSuccess = {},
            onError = {}
        )

        withTimeout(500) {
            while (viewModel.state.deletingWorktreePath == null) {
                delay(5)
            }
        }
        assertEquals("/tmp/worktree", viewModel.state.deletingWorktreePath)

        gate.complete(Unit)

        withTimeout(500) {
            while (viewModel.state.deletingWorktreePath != null) {
                delay(5)
            }
        }
        assertEquals(null, viewModel.state.deletingWorktreePath)
    }

    @Test
    fun `scanIgnoredFiles toggles isScanning and updates list`() = runBlocking {
        val scanStarted = CompletableDeferred<Unit>()
        val scanGate = CompletableDeferred<Unit>()
        val expected = listOf(
            IgnoredFileInfo(
                relativePath = "build/",
                type = IgnoredFileInfo.FileType.DIRECTORY,
                sizeBytes = null,
                selected = false
            )
        )
        val scanner = FakeIgnoredFilesScanner(
            scanHandler = {
                scanStarted.complete(Unit)
                scanGate.await()
                Result.success(expected)
            }
        )
        val viewModel = WorktreeViewModel(
            project = fakeProject(),
            coroutineScope = this,
            repository = FakeWorktreeRepository(),
            ignoredFilesService = scanner,
            fileOpsService = FakeFileOperations
        )

        val job = launch { viewModel.scanIgnoredFiles() }

        scanStarted.await()
        assertTrue(viewModel.state.isScanning)

        scanGate.complete(Unit)
        job.join()

        assertFalse(viewModel.state.isScanning)
        assertEquals(expected, viewModel.state.ignoredFiles)
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

    private class FakeWorktreeRepository(
        private val fetchHandler: suspend () -> Result<List<WorktreeInfo>> = { Result.success(emptyList()) },
        private val createHandler: suspend () -> Result<com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult> = {
            Result.success(com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult(path = "/tmp/worktree", created = true))
        },
        private val deleteHandler: suspend () -> Result<Unit> = { Result.success(Unit) }
    ) : WorktreeRepositoryContract {
        override suspend fun fetchWorktrees(): Result<List<WorktreeInfo>> = fetchHandler()

        override suspend fun createWorktree(
            name: String,
            branchName: String
        ): Result<com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult> = createHandler()

        override suspend fun deleteWorktree(worktreePath: String, branchName: String?): Result<Unit> = deleteHandler()
    }

    private class FakeIgnoredFilesScanner(
        private val scanHandler: suspend () -> Result<List<IgnoredFileInfo>> = { Result.success(emptyList()) }
    ) : IgnoredFilesScanner {
        override suspend fun scanIgnoredFiles(projectPath: String): Result<List<IgnoredFileInfo>> = scanHandler()
    }

    private object FakeFileOperations : FileOperations {
        override suspend fun copyItems(
            sourceRoot: Path,
            destRoot: Path,
            items: List<IgnoredFileInfo>
        ): CopyResult {
            return CopyResult(succeeded = emptyList(), failed = emptyList())
        }
    }
}
