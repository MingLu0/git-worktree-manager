package com.purringlabs.gitworktree.gitworktreemanager.models

/**
 * Result of a file copy operation
 *
 * @property succeeded List of relative paths that were successfully copied
 * @property failed List of pairs (relative path, error message) for failed copies
 */
data class CopyResult(
    val succeeded: List<String>,
    val failed: List<Pair<String, String>>
) {
    /**
     * True if any copy operations failed
     */
    val hasFailures: Boolean = failed.isNotEmpty()

    /**
     * Number of successful copy operations
     */
    val successCount: Int = succeeded.size

    /**
     * Number of failed copy operations
     */
    val failureCount: Int = failed.size
}
