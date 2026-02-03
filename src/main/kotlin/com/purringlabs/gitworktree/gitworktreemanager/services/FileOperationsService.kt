package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.models.CopyResult
import com.purringlabs.gitworktree.gitworktreemanager.models.CopyFilesEvent
import com.purringlabs.gitworktree.gitworktreemanager.models.ErrorType
import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo
import com.purringlabs.gitworktree.gitworktreemanager.models.StructuredError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Service for file and directory operations between worktrees
 *
 * Handles copying files and directories while preserving permissions and structure.
 * Continues operation even when individual files fail, collecting errors for reporting.
 */
@Service(Service.Level.PROJECT)
class FileOperationsService(private val project: Project) : FileOperations {

    companion object {
        fun getInstance(project: Project): FileOperationsService {
            return project.getService(FileOperationsService::class.java)
        }
    }

    private val telemetryService: TelemetryService
        get() = TelemetryServiceImpl.getInstance()

    /**
     * Copies files and directories from source to destination
     *
     * @param sourceRoot The root directory to copy from
     * @param destRoot The root directory to copy to
     * @param items List of files/directories to copy (relative paths)
     * @return CopyResult containing succeeded and failed operations
     */
    override suspend fun copyItems(
        sourceRoot: Path,
        destRoot: Path,
        items: List<IgnoredFileInfo>
    ): CopyResult = withContext(Dispatchers.IO) {
        val operationId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        for (item in items) {
            if (!item.selected) continue

            // Normalize paths and validate they stay within root directories
            val sourcePath = sourceRoot.resolve(item.relativePath).normalize()
            val destPath = destRoot.resolve(item.relativePath).normalize()

            // Security: Prevent path traversal attacks
            if (!sourcePath.startsWith(sourceRoot) || !destPath.startsWith(destRoot)) {
                failed.add(item.relativePath to "Invalid path: outside allowed directory")
                continue
            }

            try {
                if (item.type == IgnoredFileInfo.FileType.DIRECTORY) {
                    copyDirectoryRecursively(sourcePath, destPath)
                } else {
                    copyFile(sourcePath, destPath)
                }
                succeeded.add(item.relativePath)
            } catch (e: NoSuchFileException) {
                failed.add(item.relativePath to "File not found (may have been deleted)")
            } catch (e: AccessDeniedException) {
                failed.add(item.relativePath to "Permission denied")
            } catch (e: FileSystemException) {
                failed.add(item.relativePath to "File system error: ${e.reason ?: e.message}")
            } catch (e: IOException) {
                failed.add(item.relativePath to "I/O error: ${e.message}")
            } catch (e: Exception) {
                failed.add(item.relativePath to "Unexpected error: ${e.message}")
            }
        }

        val result = CopyResult(succeeded = succeeded, failed = failed)
        val failureCount = failed.size
        val error = if (failureCount > 0) {
            StructuredError(
                errorType = ErrorType.FILE_OPERATION_FAILED.name,
                errorMessage = "Failed to copy $failureCount of ${succeeded.size + failureCount} items",
                gitCommand = null,
                gitExitCode = null,
                gitErrorOutput = null,
                stackTrace = null
            )
        } else {
            null
        }

        telemetryService.recordOperation(
            CopyFilesEvent(
                operationId = operationId,
                startTime = startTime,
                durationMs = System.currentTimeMillis() - startTime,
                success = failureCount == 0,
                context = telemetryService.getContext(),
                itemCount = items.count { it.selected },
                successCount = succeeded.size,
                failureCount = failureCount,
                error = error
            )
        )

        result
    }

    /**
     * Copies a single file, creating parent directories if needed
     */
    private fun copyFile(source: Path, dest: Path) {
        // Create parent directories if they don't exist
        dest.parent?.let { Files.createDirectories(it) }

        Files.copy(
            source,
            dest,
            StandardCopyOption.COPY_ATTRIBUTES,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    /**
     * Recursively copies a directory and all its contents
     *
     * Uses Files.walkFileTree to traverse the source directory,
     * copying each file and creating subdirectories as needed.
     *
     * Security: Does NOT follow symbolic links to prevent directory escape
     * and infinite loops from circular references.
     */
    private fun copyDirectoryRecursively(source: Path, dest: Path) {
        // Use EnumSet.noneOf to explicitly disable following symbolic links
        val options = java.util.EnumSet.noneOf(FileVisitOption::class.java)
        Files.walkFileTree(source, options, Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val targetDir = dest.resolve(source.relativize(dir))
                try {
                    Files.createDirectories(targetDir)
                } catch (e: FileAlreadyExistsException) {
                    // Directory already exists, continue
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val targetFile = dest.resolve(source.relativize(file))
                Files.copy(
                    file,
                    targetFile,
                    StandardCopyOption.COPY_ATTRIBUTES,
                    StandardCopyOption.REPLACE_EXISTING
                )
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                // Re-throw the exception to be caught by the outer try-catch
                throw exc ?: IOException("Failed to visit file: $file")
            }
        })
    }
}
