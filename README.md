# Git Worktree Manager

A powerful IntelliJ IDEA/Android Studio plugin that simplifies Git worktree management, allowing you to work on multiple features simultaneously without the overhead of switching branches or maintaining multiple clones.

## Features

- **List Worktrees**: View all worktrees in your repository with their branch names, paths, and commit information
- **Create Worktrees**: Create new worktrees with a simple dialog interface
- **Auto-Open in New Window**: Automatically opens newly created worktrees in a separate IDE window
- **Delete Worktrees**: Remove worktrees with one click (with confirmation dialog)
- **Modern UI**: Built with Jetpack Compose for a native, responsive user experience

## Why Use Git Worktrees?

Git worktrees allow you to check out multiple branches from the same repository into separate directories. This is especially useful for Android developers who need to:

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

### From Marketplace (Coming Soon)

Search for "Git Worktree Manager" in the IntelliJ Plugin Marketplace.

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

## Development

This plugin is built with:
- Kotlin
- IntelliJ Platform SDK
- Jetpack Compose for Desktop (Jewel)
- Git4Idea (IntelliJ's Git integration API)

### Building

```bash
./gradlew build
```

### Running

```bash
./gradlew runIde
```

## License

MIT License - See LICENSE file for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Author

Built with ❤️ by Purring Labs

## Acknowledgments

- Inspired by [this article](https://medium.com/@domen.lanisnik/increase-productivity-with-git-worktrees-as-an-android-developer-c7e8b99eeab5) on Git worktrees for Android developers
- Built with [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
