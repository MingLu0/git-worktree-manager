package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class GitWorktreeServiceRemoteBranchSupportTest {

    @Test
    fun `deriveLocalBranchName strips remote prefix only`() {
        val service = GitWorktreeService(fakeProject())

        assertEquals("feature/foo", service.deriveLocalBranchName("origin/feature/foo"))
        assertEquals("bugfix/bar", service.deriveLocalBranchName("upstream/bugfix/bar"))
        assertEquals("main", service.deriveLocalBranchName("origin/main"))
    }

    private fun fakeProject(): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
            InvocationHandler { _, method, _ ->
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
            }
        ) as Project
    }
}
