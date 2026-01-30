# Manual Testing Checklist - Copy Ignored Files Feature

## Prerequisites
- Git 2.11.0+ installed
- IntelliJ IDEA with plugin installed
- Test Git repository with `.gitignore` file

## Test Setup
1. Create a test Git repository with ignored files:
   ```bash
   mkdir test-worktree-repo && cd test-worktree-repo
   git init
   echo "*.log" > .gitignore
   echo "node_modules/" >> .gitignore
   echo "build/" >> .gitignore

   # Create some ignored files
   echo "test" > test.log
   mkdir node_modules && echo "test" > node_modules/package.json
   mkdir build && echo "test" > build/output.jar

   # Create a normal file and commit
   echo "# Test" > README.md
   git add README.md .gitignore
   git commit -m "Initial commit"
   ```

## Test Cases

### TC1: Happy Path - Copy Selected Files
**Steps:**
1. Open test repository in IntelliJ
2. Open Git Worktree Manager tool window
3. Click "Create Worktree"
4. Enter worktree name: `feature-test`
5. Enter branch name: `feature-test`
6. Click "Yes" when asked "Do you want to copy ignored files?"
7. Wait for scan to complete
8. Verify ignored files list shows:
   - `test.log` (File)
   - `node_modules/` (Directory)
   - `build/` (Directory)
9. Select `test.log` and `node_modules/` (leave `build/` unchecked)
10. Click "OK"
11. Wait for worktree creation

**Expected:**
- Worktree created successfully
- New window opens with the worktree
- Copy result dialog shows:
  - "Successfully copied 2 file(s)"
  - No failures
- Verify in new worktree:
  - `test.log` exists
  - `node_modules/` directory exists with contents
  - `build/` directory does NOT exist

### TC2: No Ignored Files
**Steps:**
1. Create a clean repository with no ignored files
2. Click "Create Worktree"
3. Enter names
4. Click "Yes" to copy ignored files

**Expected:**
- Info message: "No ignored files found."
- Worktree created normally without selection dialog

### TC3: User Cancels File Selection
**Steps:**
1. Follow TC1 steps 1-8
2. Click "Cancel" on the file selection dialog

**Expected:**
- Worktree is still created (without copying files)
- New window opens
- Success message shown
- No files copied

### TC4: User Says "No" to Copy
**Steps:**
1. Click "Create Worktree"
2. Enter names
3. Click "No" when asked about copying ignored files

**Expected:**
- No scan performed
- Worktree created immediately
- No file selection dialog shown

### TC5: Git Scan Fails
**Steps:**
1. Test with a repository where Git is not available or broken
2. Click "Create Worktree"
3. Enter names
4. Click "Yes" to copy ignored files

**Expected:**
- Error dialog: "Failed to scan ignored files: [error message]"
- Worktree NOT created

### TC6: Large Directory Copy
**Setup:**
```bash
mkdir large_ignored
for i in {1..1000}; do echo "file $i" > large_ignored/file$i.txt; done
echo "large_ignored/" >> .gitignore
```

**Steps:**
1. Click "Create Worktree"
2. Select the `large_ignored/` directory
3. Confirm

**Expected:**
- Copy completes successfully (may take a few seconds)
- All 1000 files copied
- No timeout or freeze

### TC7: Permission Denied
**Setup:**
```bash
touch readonly.log
chmod 000 readonly.log
echo "*.log" >> .gitignore
```

**Steps:**
1. Try to copy `readonly.log`

**Expected:**
- Copy result dialog shows:
  - Failed: `readonly.log - Permission denied`
  - Worktree still created
  - Other files copied successfully

### TC8: Path Traversal Security
**Manual verification:**
- Review `FileOperationsService.kt` line 52
- Verify path normalization and boundary checks prevent `../` escapes

**Expected:**
- Code contains `normalize()` and `startsWith()` checks

### TC9: Symbolic Links
**Setup:**
```bash
ln -s /etc/passwd symlink.log
echo "*.log" >> .gitignore
```

**Steps:**
1. Try to copy `symlink.log`

**Expected:**
- File copied correctly (symlink itself, not target)
- OR fails gracefully with error message
- No infinite loop or directory escape

### TC10: File Deleted During Scan
**Steps:**
1. Start worktree creation with copy
2. While scan dialog is open, delete an ignored file from filesystem
3. Select the (now missing) file
4. Confirm

**Expected:**
- Copy result shows failure for missing file
- Other files copied successfully
- No crash

### TC11: Select All / Deselect All
**Steps:**
1. Open file selection dialog
2. Click "Select All"
3. Verify all checkboxes checked
4. Click "Deselect All"
5. Verify all checkboxes unchecked
6. Manually select 2 files
7. Click "OK"

**Expected:**
- Only the 2 manually selected files are copied

### TC12: Empty Directory
**Setup:**
```bash
mkdir empty_dir
echo "empty_dir/" >> .gitignore
```

**Steps:**
1. Select `empty_dir/` for copying

**Expected:**
- Empty directory created in new worktree
- No errors

### TC13: Nested .gitignore Files
**Setup:**
```bash
mkdir subproject
echo "*.tmp" > subproject/.gitignore
touch subproject/test.tmp
```

**Steps:**
1. Verify scan finds `subproject/test.tmp`

**Expected:**
- Nested gitignore rules respected
- File appears in ignored files list

## Performance Testing

### PT1: Large Repository
- Test with repository containing 10,000+ files
- Verify scan completes in < 5 seconds
- UI remains responsive

### PT2: Deep Directory Structure
- Create 100-level deep directory: `a/b/c/d/.../file.log`
- Verify copy succeeds without stack overflow

### PT3: Many Ignored Files
- Create 5000+ ignored files
- Verify dialog renders all items
- Verify scrolling works smoothly

## Regression Testing

### RT1: Basic Worktree Creation Still Works
**Steps:**
1. Create worktree without copying files (answer "No")

**Expected:**
- Works exactly as before feature was added
- No new errors or delays

### RT2: Delete Worktree Still Works
**Steps:**
1. Delete an existing worktree

**Expected:**
- Delete confirmation dialog appears
- Deletion succeeds
- No changes to behavior

## Checklist Summary

- [ ] TC1: Happy Path - Copy Selected Files
- [ ] TC2: No Ignored Files
- [ ] TC3: User Cancels File Selection
- [ ] TC4: User Says "No" to Copy
- [ ] TC5: Git Scan Fails
- [ ] TC6: Large Directory Copy
- [ ] TC7: Permission Denied
- [ ] TC8: Path Traversal Security
- [ ] TC9: Symbolic Links
- [ ] TC10: File Deleted During Scan
- [ ] TC11: Select All / Deselect All
- [ ] TC12: Empty Directory
- [ ] TC13: Nested .gitignore Files
- [ ] PT1: Large Repository
- [ ] PT2: Deep Directory Structure
- [ ] PT3: Many Ignored Files
- [ ] RT1: Basic Worktree Creation Still Works
- [ ] RT2: Delete Worktree Still Works

## Notes
- Test on multiple platforms: Windows, macOS, Linux
- Test with different Git versions (2.11+, latest)
- Monitor IntelliJ IDEA logs for warnings/errors
- Test with different file encodings and special characters
