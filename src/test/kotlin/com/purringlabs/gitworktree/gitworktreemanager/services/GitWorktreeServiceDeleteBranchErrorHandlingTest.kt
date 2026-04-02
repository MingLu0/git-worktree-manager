package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.DeleteWorktreeResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class GitWorktreeServiceDeleteBranchErrorHandlingTest {

    @Test
    fun `delete result can represent missing branch as non-fatal cleanup case`() {
        val result = DeleteWorktreeResult(
            worktreeRemoved = true,
            branchDeleted = true,
            branchDeleteError = null
        )

        assertTrue(result.worktreeRemoved)
        assertTrue(result.branchDeleted)
        assertNull(result.branchDeleteError)
    }

    @Test
    fun `delete result can still represent real branch deletion failure`() {
        val result = DeleteWorktreeResult(
            worktreeRemoved = true,
            branchDeleted = false
        )

        assertTrue(result.worktreeRemoved)
        assertFalse(result.branchDeleted)
    }

    @Suppress("unused")
    private fun fakeProject(): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "isDisposed" -> false
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
            }
        ) as Project
    }
}
