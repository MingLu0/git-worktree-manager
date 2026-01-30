# Copy Ignored Files to New Worktree - Design Document

**Date:** 2026-01-30
**Feature:** Copy .gitignore'd files to new worktrees
**Status:** Design Approved

---

## Problem Statement

When developers create Git worktrees, locally ignored files (configuration files, environment variables, IDE settings, build artifacts, etc.) are not copied to the new worktree. This forces developers to manually recreate or copy these files, which is error-prone and time-consuming. The problem is particularly acute for files like `.env`, local IDE configurations, or development database files that are necessary for the worktree to function but are intentionally excluded from version control.

---

## Success Criteria

The feature is complete when:

1. **Detection**: The plugin can accurately identify all files and directories ignored by `.gitignore` in the current worktree
2. **User Control**: Users can opt-in via a checkbox in the "Create Worktree" dialog
3. **Selection Interface**: When opted-in, users see a checklist dialog showing all ignored items before worktree creation
4. **Copying**: Selected files and directories are copied to the corresponding paths in the new worktree after creation
5. **Error Resilience**: Copy failures (permissions, disk space) don't abort the entire operation - the plugin continues and shows a summary
6. **User Feedback**: Users see clear success/failure messages indicating which files were copied and which failed

---

## Constraints

- Must not modify existing worktree creation behavior when checkbox is unchecked
- Must not copy files that would cause conflicts or break the new worktree
- Must preserve file permissions and directory structures when copying
- Must work with the existing clean architecture (services → repository → viewmodel → UI)
- Requires Git 2.11.0+ for reliable `--porcelain=v2` support
- Must use IntelliJ Messages API for dialogs (not experimental Compose dialogs)

---

## High-Level Approach

### Architecture Overview

We'll extend the existing clean architecture by adding two new services (`IgnoredFilesService` and `FileOperationsService`) and enhancing the worktree creation flow in the UI and ViewModel.

### Workflow

```
User clicks "Create Worktree"
  → Enters name and branch
  → [NEW] Sees "Copy ignored files" checkbox
  → If checked:
     → System scans current worktree for ignored files
     → Shows checklist dialog with all ignored items
     → User selects which files/dirs to copy
  → System creates new worktree (existing behavior)
  → If ignored files were selected:
     → System copies selected items to new worktree
     → Shows success/failure summary
  → Opens new worktree in IDE window (existing behavior)
```

### Component Design

1. **IgnoredFilesService** (new service layer):
   - `scanIgnoredFiles(projectPath: String): List<IgnoredFileInfo>`
   - Uses `git status --ignored --porcelain=v2` to detect ignored files
   - Returns structured data about each ignored item (path, type, size)

2. **FileOperationsService** (new service layer):
   - `copyFiles(source: Path, dest: Path, items: List<Path>): CopyResult`
   - Handles file/directory copying with error handling
   - Returns success/failure status for each item

3. **WorktreeViewModel** (enhanced and refactored):
   - Refactor to use constructor injection for all dependencies
   - Add new service dependencies via constructor
   - Add ignored files workflow state and methods

4. **MyToolWindow.kt** (enhanced UI):
   - Update ViewModel instantiation to inject all dependencies
   - Add checkbox to create worktree dialog using IntelliJ Messages API
   - Add new ignored files selection dialog (Messages API)
   - Add copy result dialog (Messages API)

### Data Models

```kotlin
data class IgnoredFileInfo(
    val relativePath: String,
    val type: FileType,  // FILE or DIRECTORY
    val sizeBytes: Long?,
    val selected: Boolean = false
)

data class CopyResult(
    val succeeded: List<String>,
    val failed: List<Pair<String, String>>  // path to error message
)
```

---

## Detailed Component Design

### 1. IgnoredFilesService (New)

**Location:** `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/services/IgnoredFilesService.kt`

**Service Declaration:**
```kotlin
@Service(Service.Level.PROJECT)
class IgnoredFilesService(private val project: Project) {
    companion object {
        fun getInstance(project: Project): IgnoredFilesService {
            return project.getService(IgnoredFilesService::class.java)
        }
    }
}
```

**Responsibilities:**
- Detect files/directories ignored by `.gitignore`
- Use Git commands to get accurate ignore status
- Return structured data for UI presentation

**Key Methods:**

```kotlin
suspend fun scanIgnoredFiles(projectPath: String): Result<List<IgnoredFileInfo>>
```

**Implementation Strategy:**
- Use `git status --ignored --porcelain=v2` for accurate detection
- Parse output to extract ignored file paths (lines starting with `! `)
- Use `java.nio.file.Files` to get file metadata (size, type)
- Run on `Dispatchers.IO` since it's I/O intensive

**Git Command Details:**
- Command: `git status --ignored --porcelain=v2`
- Minimum Git version: 2.11.0 (when `--porcelain=v2` was introduced)
- Output format: Lines starting with `! ` indicate ignored files/directories
- Example output:
  ```
  ! .env
  ! .idea/workspace.xml
  ! node_modules/
  ```

**Error Handling:**
- Return `Result.failure` if Git command fails
- Return empty list if no ignored files found
- Handle permission errors gracefully

---

### 2. FileOperationsService (New)

**Location:** `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/services/FileOperationsService.kt`

**Service Declaration:**
```kotlin
@Service(Service.Level.PROJECT)
class FileOperationsService(private val project: Project) {
    companion object {
        fun getInstance(project: Project): FileOperationsService {
            return project.getService(FileOperationsService::class.java)
        }
    }
}
```

**Responsibilities:**
- Copy files and directories between worktrees
- Preserve permissions and directory structure
- Handle errors without aborting entire operation

**Key Methods:**

```kotlin
suspend fun copyItems(
    sourceRoot: Path,
    destRoot: Path,
    items: List<IgnoredFileInfo>
): CopyResult
```

**Implementation Strategy:**
- Use `java.nio.file.Files.copy()` with `COPY_ATTRIBUTES` and `REPLACE_EXISTING`
- **IMPORTANT:** For directories, use `Files.walkFileTree()` to recursively copy contents
  - `Files.copy()` cannot copy non-empty directories directly
  - Must traverse directory tree and copy each file individually
- Catch exceptions per-file and continue copying
- Build `CopyResult` tracking successes and failures

**Directory Copying Pattern:**
```kotlin
fun copyDirectoryRecursively(source: Path, dest: Path) {
    Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            val targetDir = dest.resolve(source.relativize(dir))
            Files.createDirectories(targetDir)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.copy(file, dest.resolve(source.relativize(file)),
                      StandardCopyOption.COPY_ATTRIBUTES,
                      StandardCopyOption.REPLACE_EXISTING)
            return FileVisitResult.CONTINUE
        }
    })
}
```

**Error Handling:**
- Catch `IOException`, `AccessDeniedException`, `FileSystemException` per item
- Continue copying remaining items after failure
- Include error message in `CopyResult.failed`

---

### 3. Enhanced Data Models

**Location:** `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/models/IgnoredFileInfo.kt`

```kotlin
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

    fun displayName(): String = relativePath

    fun displaySize(): String = when {
        type == FileType.DIRECTORY -> "(directory)"
        sizeBytes == null -> "(unknown size)"
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
        else -> "${sizeBytes / (1024 * 1024)} MB"
    }
}

data class CopyResult(
    val succeeded: List<String>,
    val failed: List<Pair<String, String>>
) {
    val hasFailures: Boolean = failed.isNotEmpty()
    val successCount: Int = succeeded.size
    val failureCount: Int = failed.size
}
```

---

### 4. ViewModel and State Changes

**Enhanced WorktreeState**

**Location:** `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/viewmodel/WorktreeState.kt`

```kotlin
data class WorktreeState(
    val worktrees: List<WorktreeInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,

    // NEW: Ignored files workflow state
    val copyIgnoredFilesEnabled: Boolean = false,
    val ignoredFiles: List<IgnoredFileInfo> = emptyList(),
    val isScanning: Boolean = false,
    val scanError: String? = null,
    val copyResult: CopyResult? = null
)
```

**State Transitions:**

1. User checks "Copy ignored files" → `copyIgnoredFilesEnabled = true`
2. User clicks "Create" → `isScanning = true`
3. Scan completes → `ignoredFiles = [...], isScanning = false`
4. User selects files and confirms → worktree creation proceeds
5. Copy completes → `copyResult = CopyResult(...)`

---

**Refactored WorktreeViewModel with Constructor Injection**

**Location:** `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/viewmodel/WorktreeViewModel.kt`

**BEFORE (Current Implementation):**
```kotlin
class WorktreeViewModel(
    private val project: Project,
    private val scope: CoroutineScope
) {
    private val repository = WorktreeRepository(project)
}
```

**AFTER (Refactored with Constructor Injection):**
```kotlin
class WorktreeViewModel(
    private val project: Project,
    private val scope: CoroutineScope,
    private val repository: WorktreeRepository,
    private val ignoredFilesService: IgnoredFilesService,
    private val fileOpsService: FileOperationsService
) {
    // All dependencies now injected via constructor
}
```

**Benefits:**
- **Testability:** Easy to inject mocks for unit testing
- **Explicitness:** All dependencies visible at call site
- **Flexibility:** Can inject different implementations
- **Consistency:** Same pattern for all dependencies

**New Methods:**

```kotlin
// Step 1: Scan for ignored files
fun scanIgnoredFiles() {
    state = state.copy(isScanning = true, scanError = null)
    scope.launch {
        val result = ignoredFilesService.scanIgnoredFiles(project.basePath ?: "")
        state = state.copy(
            isScanning = false,
            ignoredFiles = result.getOrNull() ?: emptyList(),
            scanError = result.exceptionOrNull()?.message
        )
    }
}

// Step 2: Update selected files
fun updateIgnoredFileSelection(updatedList: List<IgnoredFileInfo>) {
    state = state.copy(ignoredFiles = updatedList)
}

// Step 3: Create worktree with optional file copying
suspend fun createWorktreeWithIgnoredFiles(
    worktreeName: String,
    branchName: String,
    selectedFiles: List<IgnoredFileInfo>,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    // 1. Create worktree (existing logic)
    createWorktree(worktreeName, branchName,
        onSuccess = {
            // 2. If files selected, copy them
            if (selectedFiles.isNotEmpty()) {
                scope.launch {
                    copyIgnoredFiles(worktreeName, selectedFiles)
                }
            }
            onSuccess()
        },
        onError = onError
    )
}

private suspend fun copyIgnoredFiles(
    worktreeName: String,
    selectedFiles: List<IgnoredFileInfo>
) {
    val sourceRoot = Paths.get(project.basePath ?: return)
    val destRoot = Paths.get(repository.getWorktreePath(worktreeName).getOrNull() ?: return)

    val result = fileOpsService.copyItems(sourceRoot, destRoot, selectedFiles)
    state = state.copy(copyResult = result)
}
```

---

### 5. Updated MyToolWindow.kt (ViewModel Instantiation)

**Location:** `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/MyToolWindow.kt`

**BEFORE (Current):**
```kotlin
val viewModel = remember { WorktreeViewModel(project, coroutineScope) }
```

**AFTER (With Dependency Injection):**
```kotlin
val viewModel = remember {
    WorktreeViewModel(
        project = project,
        scope = coroutineScope,
        repository = WorktreeRepository(project),
        ignoredFilesService = IgnoredFilesService.getInstance(project),
        fileOpsService = FileOperationsService.getInstance(project)
    )
}
```

**Benefits:**
- Dependencies are explicitly instantiated at the call site
- Easy to see what the ViewModel depends on
- Simple to replace with mocks in tests

---

## UI Changes

### 1. Enhanced Create Worktree Dialog

**Location:** `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/MyToolWindow.kt`

**Pattern to Follow:** Use IntelliJ `Messages` API (as used for existing dialogs in MyToolWindow.kt:106-131)

**Implementation Approach:**

The current codebase uses `Messages.showInputDialog()` for worktree name and branch input. For the "Copy ignored files" checkbox, we'll use sequential dialogs (simpler, consistent with existing code):

```kotlin
// After getting worktree name and branch
val copyIgnoredFiles = Messages.showYesNoDialog(
    project,
    "Do you want to copy ignored files to the new worktree?",
    "Copy Ignored Files",
    Messages.getQuestionIcon()
) == Messages.YES
```

---

### 2. Ignored Files Selection Dialog

**Purpose:** Show list of ignored files/directories for user selection

**Implementation:** Custom `DialogWrapper` with checkbox list

**UI Structure:**

```
┌─────────────────────────────────────────┐
│ Select Ignored Files to Copy           │
├─────────────────────────────────────────┤
│ □ Select All                            │
├─────────────────────────────────────────┤
│ Scrollable list:                        │
│ ☑ .env (2 KB)                           │
│ ☑ .idea/workspace.xml (45 KB)          │
│ □ node_modules/ (directory)             │
│ ☑ local.properties (1 KB)              │
│ ...                                     │
├─────────────────────────────────────────┤
│         [Cancel]  [Create Worktree]     │
└─────────────────────────────────────────┘
```

**Implementation Pattern:**

```kotlin
class IgnoredFilesSelectionDialog(
    project: Project,
    private val ignoredFiles: List<IgnoredFileInfo>,
    private val onSelected: (List<IgnoredFileInfo>) -> Unit
) : DialogWrapper(project) {

    init {
        title = "Select Ignored Files to Copy"
        init()
    }

    override fun createCenterPanel(): JComponent {
        // Create JPanel with JCheckBox list using Swing
        // Return panel
    }

    override fun doOKAction() {
        val selected = /* get selected items */
        onSelected(selected)
        super.doOKAction()
    }
}
```

**Data Flow:**
- Receives `state.ignoredFiles` from ViewModel
- User toggles checkboxes
- On "Create Worktree", returns selected items via callback
- Calls `viewModel.createWorktreeWithIgnoredFiles()`

---

### 3. Copy Result Dialog

**Purpose:** Show summary of copy operation after worktree creation

**Implementation:** `Messages.showMessageDialog()` with formatted text

```kotlin
val message = buildString {
    if (copyResult.successCount > 0) {
        appendLine("✓ Successfully copied ${copyResult.successCount} items:")
        copyResult.succeeded.forEach { appendLine("  • $it") }
        appendLine()
    }

    if (copyResult.failureCount > 0) {
        appendLine("✗ Failed to copy ${copyResult.failureCount} items:")
        copyResult.failed.forEach { (path, error) ->
            appendLine("  • $path ($error)")
        }
    }
}

Messages.showMessageDialog(
    project,
    message,
    "Ignored Files Copy Summary",
    if (copyResult.hasFailures) Messages.getWarningIcon() else Messages.getInformationIcon()
)
```

---

## Implementation Considerations

### 1. Git Command Strategy

**Challenge:** Accurately detecting ignored files without false positives

**Recommended Command:** `git status --ignored --porcelain=v2`

**Why this command:**
- Respects all `.gitignore` rules (including nested `.gitignore` files)
- Includes global Git ignore rules
- Provides machine-readable output (porcelain format)
- Distinguishes between ignored files and untracked files
- **Requires Git 2.11.0+** (when `--porcelain=v2` was introduced)

**Alternative considered:** `git check-ignore --verbose <path>`
- Requires iterating through file tree first
- More complex parsing
- Less efficient for large codebases

**Parsing example:**

```
! .env
! .idea/workspace.xml
! node_modules/
```

Lines starting with `! ` indicate ignored files/directories.

---

### 2. Performance Optimization

**Concerns:**
- Large directories like `node_modules/` could be hundreds of MB
- Scanning deep directory trees is slow
- UI should remain responsive

**Strategies:**

1. **Background Scanning:** Run `scanIgnoredFiles()` on `Dispatchers.IO`
2. **Lazy Copying:** Use `Flow` or `Channel` to report progress for large copies
3. **Size Warnings:** Flag directories >100MB with warning icon in selection dialog
4. **Cancellation Support:** Allow user to cancel long-running scans/copies
5. **Timeout:** If scan takes >10 seconds, show "Still scanning..." message

---

### 3. Edge Cases

**Symlinks:**
- Detection: Check if `Files.isSymbolicLink(path)` returns true
- Handling: Copy symlink target, not the link itself (use `FOLLOW_LINKS`)
- UI: Show "(symlink)" indicator in selection dialog

**Nested .gitignore files:**
- Git command handles this automatically
- No special logic needed

**Binary files:**
- Copy as-is using `Files.copy()`
- No encoding/decoding needed

**File conflicts:**
- Use `REPLACE_EXISTING` flag - destination is a fresh worktree, should be safe
- If file exists (shouldn't happen), overwrite it

**Empty directories:**
- If directory is empty but ignored, Git status won't list it
- Accept this limitation (empty ignored dirs are rare edge case)

**Permission denied:**
- Catch `AccessDeniedException` during copy
- Add to `CopyResult.failed` with error message
- Continue copying other files

**Non-empty directories:**
- **CRITICAL:** `Files.copy()` cannot copy non-empty directories
- Must use `Files.walkFileTree()` to recursively copy contents
- Implement custom directory copy logic (see FileOperationsService section)

---

### 4. Testing Strategy

**Unit Tests:**
- Mock Git commands and test parsing logic
- Test file copying with temp directories
- Test error handling (missing files, permission errors)
- **Easy to test with constructor injection** - inject mock services

**Integration Tests:**
- Create test Git repo with `.gitignore`
- Add ignored files and verify detection
- Test end-to-end copy workflow

**Manual Testing Scenarios:**
- Large directories (e.g., actual `node_modules/`)
- Permission-restricted files
- Symlinks
- Various file types (text, binary, images)
- Multiple nested `.gitignore` files

**Example Test with Constructor Injection:**
```kotlin
@Test
fun `test scan ignored files success`() = runTest {
    val mockService = mock<IgnoredFilesService>()
    whenever(mockService.scanIgnoredFiles(any())).thenReturn(
        Result.success(listOf(/* test data */))
    )

    val viewModel = WorktreeViewModel(
        project = mockProject,
        scope = testScope,
        repository = mockRepository,
        ignoredFilesService = mockService,
        fileOpsService = mockFileOps
    )

    viewModel.scanIgnoredFiles()
    // Assert state updates
}
```

---

## Open Questions

1. **Should we remember user's "Copy ignored files" preference?**
   - Store in IDE settings and pre-check checkbox on next worktree creation?
   - Or always default to unchecked?
   - **Recommendation:** Default to unchecked for safety, but consider adding preference later

2. **Should we support patterns/filters for auto-selecting files?**
   - e.g., "Always copy .env files" or "Never copy node_modules/"
   - **Recommendation:** Start simple (manual selection), add patterns in future version if users request it

3. **Should we show a progress bar during copying?**
   - For large directories, copying could take significant time
   - **Recommendation:** Show indeterminate progress spinner initially, add progress bar if users report slow copies

4. **Should we log copy operations?**
   - Useful for debugging but could clutter IDE logs
   - **Recommendation:** Log to IntelliJ's log at DEBUG level, errors at WARN level

5. **How to handle files that appear/disappear during scan?**
   - File could be deleted after scan but before copy
   - **Recommendation:** Catch `NoSuchFileException` during copy, add to failures, continue

---

## Out of Scope

The following are explicitly NOT included in this design:

1. **Automatic synchronization:** No ongoing sync of ignored files between worktrees
2. **Selective sync:** No "sync only .env changes" or similar features
3. **Conflict resolution:** No merge/diff UI if files already exist in destination
4. **Undo functionality:** No rollback if user regrets copying certain files
5. **Remote worktree support:** Only local worktrees supported (Git worktrees are always local)
6. **Compression:** No ZIP/archive creation for large copies
7. **Cloud backup:** No backing up ignored files to cloud storage

---

## Implementation Order

After design approval, the recommended implementation order is:

### Phase 1: Refactor Existing Code
1. Refactor `WorktreeViewModel` to use constructor injection for `WorktreeRepository`
2. Update `MyToolWindow.kt` to instantiate ViewModel with injected dependencies
3. Verify existing functionality still works

### Phase 2: Core Services
4. Implement `IgnoredFilesService` with Git command execution
5. Implement `FileOperationsService` with recursive directory copy logic
6. Add unit tests for both services

### Phase 3: Data Layer
7. Create `IgnoredFileInfo` and `CopyResult` models
8. Update `WorktreeState` with new properties
9. Add new methods to `WorktreeViewModel`

### Phase 4: UI
10. Add Yes/No dialog for "Copy ignored files" option
11. Implement `IgnoredFilesSelectionDialog` (custom DialogWrapper)
12. Implement copy result message dialog
13. Wire up UI → ViewModel → Services

### Phase 5: Testing & Polish
14. Integration testing with real Git repos
15. Manual testing of edge cases
16. Performance testing with large directories
17. Documentation updates (README, inline docs)

**Estimated Effort:** 3-4 days for experienced developer (includes refactoring)

---

## Architectural Decision: Constructor Injection

### Decision
Use **required constructor parameters** (no default values) for dependency injection in `WorktreeViewModel`.

### Rationale
1. **Testability:** Easy to inject mock implementations for unit testing
2. **Explicitness:** All dependencies are visible at the call site
3. **Flexibility:** Can inject different implementations without modifying the class
4. **Type Safety:** Compiler ensures all dependencies are provided
5. **Consistency:** Same pattern for all dependencies (repository + services)

### Implementation Pattern

**ViewModel:**
```kotlin
class WorktreeViewModel(
    private val project: Project,
    private val scope: CoroutineScope,
    private val repository: WorktreeRepository,
    private val ignoredFilesService: IgnoredFilesService,
    private val fileOpsService: FileOperationsService
)
```

**Instantiation:**
```kotlin
val viewModel = remember {
    WorktreeViewModel(
        project = project,
        scope = coroutineScope,
        repository = WorktreeRepository(project),
        ignoredFilesService = IgnoredFilesService.getInstance(project),
        fileOpsService = FileOperationsService.getInstance(project)
    )
}
```

**Testing:**
```kotlin
val testViewModel = WorktreeViewModel(
    project = mockProject,
    scope = testScope,
    repository = mockRepository,
    ignoredFilesService = mockIgnoredFilesService,
    fileOpsService = mockFileOpsService
)
```

### Trade-offs
- **Pro:** Much easier to test with mocks
- **Pro:** Dependencies are explicit and clear
- **Con:** More verbose instantiation code
- **Con:** Requires refactoring existing ViewModel

**Decision:** The benefits for testability and maintainability outweigh the increased verbosity.

---

## Validation Results

The following technical assumptions were validated against the codebase:

### ✅ Validated Assumptions
- **Service Pattern:** `@Service` annotation with `getInstance(project)` is correct (matches GitWorktreeService.kt)
- **Dispatchers.IO:** Correct for blocking Git/file operations (matches WorktreeRepository.kt)
- **State Management:** Immutable data class with `copy()` is correct (matches WorktreeState.kt)

### ⚠️ Adjustments Made
- **Git Version:** Changed from Git 2.5+ to **Git 2.11.0+** (when `--porcelain=v2` was introduced)
- **Dialogs:** Using **IntelliJ Messages API** instead of experimental Compose dialogs (matches existing pattern)
- **Directory Copying:** Must use `Files.walkFileTree()` for recursive copy (Files.copy() cannot copy non-empty directories)

### 🎯 Design Decisions
- **Constructor Injection:** Using required constructor parameters for all ViewModel dependencies (user preference)
- **Refactoring:** Applying constructor injection pattern to existing `WorktreeRepository` dependency (user preference)
- **Testability:** This pattern significantly improves testability by allowing easy mock injection

### 📝 Notes
- No existing file copy operations in codebase to reference
- `java.nio.file.Files` API is recommended by JetBrains for modern plugins
- IntelliJ's `FileUtil` class is obsolete/deprecated
