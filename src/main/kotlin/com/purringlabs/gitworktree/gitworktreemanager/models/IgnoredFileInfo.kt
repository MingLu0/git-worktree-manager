package com.purringlabs.gitworktree.gitworktreemanager.models

/**
 * Represents a file or directory that is ignored by .gitignore
 *
 * @property relativePath The path relative to the project root
 * @property type Whether this is a file or directory
 * @property sizeBytes The size in bytes (null if unknown or for directories)
 * @property selected Whether this item is selected for copying
 */
data class IgnoredFileInfo(
    val relativePath: String,
    val type: FileType,
    val sizeBytes: Long?,
    val selected: Boolean = false
) {
    enum class FileType {
        FILE,
        DIRECTORY
    }

    /**
     * Returns the display name (same as relative path)
     */
    fun displayName(): String = relativePath

    /**
     * Returns a human-readable size string
     */
    fun displaySize(): String = when {
        type == FileType.DIRECTORY -> "(directory)"
        sizeBytes == null -> "(unknown size)"
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
        else -> "${sizeBytes / (1024 * 1024)} MB"
    }
}
