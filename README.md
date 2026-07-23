# Git Worktree Manager

A powerful IntelliJ IDEA/Android Studio plugin that makes Git worktrees practical for AI-assisted development. Run Claude Code across parallel branches without losing context: give each session its own worktree, then view and resume every session from the IDE in one click.

## Features

- **Resume Claude Code Sessions**: View and resume Claude Code sessions across all repository worktrees, sorted by recency — one click opens a terminal and runs `claude --resume <session-id> --fork-session`
- **List Worktrees**: View all worktrees in your repository with their branch names, paths, and commit information
- **Create Worktrees**: Create new worktrees with a simple dialog interface
- **Auto-Open in New Window**: Automatically opens newly created worktrees in a separate IDE window
- **Delete Worktrees**: Remove worktrees with one click (with confirmation dialog)
- **Modern UI**: Built with Jetpack Compose for a native, responsive user experience

## Why Use Git Worktrees?

Git worktrees allow you to check out multiple branches from the same repository into separate directories. This is especially powerful when you use AI agents like Claude Code: each branch can keep its own session context, and branch switching no longer resets the agent. It's also useful for Android developers who need to:

- Work on multiple features simultaneously
- Quickly switch between bug fixes and feature development
- Run parallel CI/CD pipelines
- Compare implementations across branches
- Keep Gradle build caches separate (faster builds!)

Read more about the benefits: [Increase Productivity with Git Worktrees](https://medium.com/@domen.lanisnik/increase-productivity-with-git-worktrees-as-an-android-developer-c7e8b99eeab5)

## Installation

### From Source

1. Clone this repository
2. Run `./gradlew build`
3. Install the plugin from `build/distributions/git-worktree-manager-*.zip`

### From Marketplace 

Search for "Git Worktree Manager" in the IntelliJ Plugin Marketplace.
https://plugins.jetbrains.com/plugin/29905-git-worktree-manager?noRedirect=true

## Usage

### Opening the Tool Window

1. Open your project in IntelliJ IDEA or Android Studio
2. Look for the "Git Worktrees" tool window (usually in the bottom panel)
3. Click to expand the tool window

### Creating a Worktree

1. Click the "Create Worktree" button
2. Enter a name for the worktree (e.g., "feature-auth")
3. Enter a branch name (e.g., "feature/auth")
4. The plugin will:
   - Create the worktree in the parent directory (e.g., `../myproject-feature-auth`)
   - Create a new branch
   - Automatically open the worktree in a new IDE window

### Resuming Claude Code Sessions

1. Click the `Claude Sessions` toggle in the tool window
2. Find a session from the current project or another repository worktree
3. Click `Resume`
4. The plugin opens a terminal and runs `claude --resume <session-id> --fork-session`

Claude sessions are discovered from Claude Code's user data for the known repository worktrees. Session resume is unavailable on Windows because Claude Code's project-path encoding differs there.

### Deleting a Worktree

1. Find the worktree in the list
2. Click the "Delete" button
3. Confirm the deletion
4. The worktree will be removed from disk

## How It Works

The plugin creates worktrees following the recommended pattern:

```
parent-directory/
├── my-project/          # Main repository
├── my-project-feature-a/ # Worktree for feature A
└── my-project-feature-b/ # Worktree for feature B
```

Each worktree:
- Shares the same Git history (no duplication)
- Has its own working directory
- Maintains separate Gradle build caches
- Can be opened in a separate IDE window

## Requirements

- IntelliJ IDEA 2025.2+ or Android Studio with equivalent platform version
- Git 2.5+ (for worktree support)

## Architecture

This plugin follows clean architecture principles with clear separation of concerns:

### Layer Structure

```
UI Layer (Composables)
    ↓
Presentation Layer (ViewModel)
    ↓
Data Layer (Repository)
    ↓
Platform Services (GitWorktreeService)
```

### Components

- **WorktreeState**: Immutable data class representing UI state (worktrees list, Claude sessions, loading state, errors)
- **WorktreeRepository**: Data access layer that wraps GitWorktreeService and handles Git repository operations
- **WorktreeViewModel**: Presentation logic layer that manages state and coordinates between Repository and UI (uses constructor dependency injection)
- **ClaudeCodeContextService**: Project-level service for listing Claude Code sessions across known repository worktrees
- **Pure Composables**: UI components with no dependencies on IntelliJ Platform APIs, making them testable and maintainable
- **DialogWrapper Dialogs**: Native IntelliJ dialogs for error details and remote branch selection

### Design Principles

- **Separation of Concerns**: UI, presentation logic, and data access are clearly separated
- **Dependency Inversion**: UI depends on abstractions (callbacks) rather than concrete implementations
- **JetBrains Best Practices**: Follows official IntelliJ Platform patterns for service management and dependency injection
- **Testability**: Pure UI functions can be tested with mock data, ViewModels can be tested independently

This architecture makes the codebase maintainable, testable, and follows IntelliJ Platform best practices for plugin development.

## Development

This plugin is built with:
- **Kotlin**: Primary programming language
- **IntelliJ Platform SDK**: Plugin framework
- **Jetpack Compose for Desktop (Jewel)**: Modern declarative UI
- **Git4Idea**: IntelliJ's Git integration API
- **Coroutines**: Asynchronous operations and state management
- **Clean Architecture**: ViewModel + Repository pattern for separation of concerns

### Building

```bash
./gradlew build
```

### Running

```bash
./gradlew runIde
```

## Support

If this plugin saves you time, consider sponsoring to help me maintain it — you’ll be buying my cats a treat.

Support link: https://github.com/sponsors/MingLu0

## License

MIT License - See LICENSE file for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Author

Built with ❤️ by PurringLabs - Ming Luo 

## Acknowledgments

- Inspired by [this article](https://medium.com/@domen.lanisnik/increase-productivity-with-git-worktrees-as-an-android-developer-c7e8b99eeab5) on Git worktrees for Android developers
- Built with [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
