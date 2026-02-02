package com.purringlabs.gitworktree.gitworktreemanager

import com.intellij.openapi.progress.util.BackgroundTaskUtil.syncPublisher
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import git4idea.repo.GitRepository
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

class MyToolWindowAutoRefreshTest : LightPlatformTestCase() {
    fun testGitRepoChangeTriggersRefresh() {
        var refreshCount = 0
        var cancelCount = 0

        val disposable = registerGitRepoAutoRefresh(
            project = project,
            requestAutoRefresh = { refreshCount++ },
            cancelAutoRefresh = { cancelCount++ }
        )

        syncPublisher(project, GitRepository.GIT_REPO_CHANGE)
            .repositoryChanged(fakeGitRepository())

        assertEquals(1, refreshCount)

        Disposer.dispose(disposable)
        assertEquals(1, cancelCount)

        syncPublisher(project, GitRepository.GIT_REPO_CHANGE)
            .repositoryChanged(fakeGitRepository())

        assertEquals(1, refreshCount)
    }

    private fun fakeGitRepository(): GitRepository {
        return Proxy.newProxyInstance(
            GitRepository::class.java.classLoader,
            arrayOf(GitRepository::class.java)
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
        } as GitRepository
    }
}
