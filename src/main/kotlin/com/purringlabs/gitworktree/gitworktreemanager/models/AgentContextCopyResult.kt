package com.purringlabs.gitworktree.gitworktreemanager.models

data class AgentContextCopyResult(
    val copied: List<String> = emptyList(),
    val skipped: List<Pair<String, String>> = emptyList(),
    val failed: List<Pair<String, String>> = emptyList()
) {
    val hasFailures: Boolean = failed.isNotEmpty()
    val hasEntries: Boolean = copied.isNotEmpty() || skipped.isNotEmpty() || failed.isNotEmpty()
    val copiedCount: Int = copied.size
    val skippedCount: Int = skipped.size
    val failureCount: Int = failed.size

    fun plus(other: AgentContextCopyResult): AgentContextCopyResult {
        return AgentContextCopyResult(
            copied = copied + other.copied,
            skipped = skipped + other.skipped,
            failed = failed + other.failed
        )
    }
}
