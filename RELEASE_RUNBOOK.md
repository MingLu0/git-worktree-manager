# Release Runbook — Git Worktree Manager (JetBrains Marketplace)

This repo uses GitHub Actions for:
- PR CI checks (test + verifyPluginStructure)
- Tag-based publishing to JetBrains Marketplace

## One-time setup

### GitHub Actions secrets (Repo → Settings → Secrets and variables → Actions)

Required for publishing:
- `INTELLIJ_PLATFORM_PUBLISHING_TOKEN`
- `PLUGIN_SIGNING_PASSWORD`

Required for CI signing (`signPlugin`):
- `PLUGIN_CERT_CHAIN_B64` (base64 of `chain.crt`)
- `PLUGIN_PRIVATE_KEY_B64` (base64 of `private.pem`)

## Standard release flow (recommended)

### 1) Bump version + change notes
Edit `build.gradle.kts`:
- Update `version = "x.y.z"`
- Update `intellijPlatform { pluginConfiguration { changeNotes = ... } }`

Commit to `master`.

### 2) (Optional) Dry-run signing/build in GitHub Actions
GitHub → Actions → **Release to JetBrains Marketplace** → Run workflow:
- `publish=false`
- `channel` empty

This should run `clean buildPlugin signPlugin` and **NOT** publish.

### 3) Publish by tag
Create an annotated tag matching the Gradle version:

```bash
git pull
# example: version=1.1.0
git tag v1.1.0

git push origin v1.1.0
```

On tag push, the workflow:
- verifies tag version == Gradle version
- runs: `./gradlew clean buildPlugin signPlugin`
- runs: `./gradlew publishPlugin`

### 4) Verify publish
JetBrains Marketplace → your plugin → Versions.

## Troubleshooting

### Tag/version mismatch
If Actions fails with “Tag version does not match Gradle version”, bump `version` in `build.gradle.kts`, merge to `master`, then re-tag.

### Signing failure
Ensure:
- `PLUGIN_SIGNING_PASSWORD` secret is correct
- `PLUGIN_CERT_CHAIN_B64` and `PLUGIN_PRIVATE_KEY_B64` decode correctly

### Slow builds / huge downloads
This plugin build downloads an IntelliJ distribution. GitHub Actions caching should make subsequent runs faster.
